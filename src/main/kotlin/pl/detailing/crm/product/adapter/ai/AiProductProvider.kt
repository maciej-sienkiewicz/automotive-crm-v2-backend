package pl.detailing.crm.product.adapter.ai

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.converter.BeanOutputConverter
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import pl.detailing.crm.product.domain.Gtin
import pl.detailing.crm.product.domain.PackageDimensions
import pl.detailing.crm.product.domain.ProductSource
import pl.detailing.crm.product.domain.ProductSpec
import pl.detailing.crm.product.domain.UnitOfMeasure
import pl.detailing.crm.product.port.ProductDataProvider
import pl.detailing.crm.product.port.ProductLookupResult
import java.math.BigDecimal

/**
 * Krok 2 łańcucha: model językowy rozpoznaje produkt po GTIN, a DRUGI, niezależny
 * model weryfikuje zgodność (punkt 2 wymagania).
 *
 * Wynikowa pewność jest iloczynem dwóch sygnałów:
 *   1. `confidence` zadeklarowana przez model odczytu,
 *   2. binarny werdykt weryfikatora (`matches`); gdy weryfikator mówi „nie", pewność
 *      spada do 0,5 i wynik nie przejdzie progu pewności — może co najwyżej wrócić
 *      jako szkic do ręcznego potwierdzenia (patrz ProductResolutionService).
 *
 * Do modelu NIE trafia nic poza kodem: ani nazwa studia, ani klient, ani wizyta.
 * To wymóg bezpieczeństwa, nie oszczędność tokenów.
 *
 * LOGOWANIE. Każde wywołanie loguje na INFO pod tagiem `[PRODUCT_AI]` DOKŁADNY prompt
 * (system + user, razem z instrukcją formatu, którą dokleja konwerter) i SUROWĄ odpowiedź
 * modelu, zanim cokolwiek zostanie sparsowane. Bez tego „NOT_FOUND" z produkcji jest
 * nie do zdiagnozowania: nie wiadomo, czy model nie zna kodu (puste pola), czy zwrócił
 * coś nieparsowalnego, czy wywołanie w ogóle nie doszło. Dlatego składamy prompt ręcznie
 * przez [BeanOutputConverter] zamiast `.entity()` — `.entity()` ukrywa surowy tekst.
 * W promptcie jest wyłącznie GTIN, więc te logi nie niosą danych osobowych.
 */
@Component
class AiProductProvider(
    @Qualifier("productLookupChatClient") private val lookupClient: ChatClient,
    @Qualifier("productVerifierChatClient") private val verifierClient: ChatClient,
    private val objectMapper: ObjectMapper
) : ProductDataProvider {

    private val log = LoggerFactory.getLogger(javaClass)

    override val source = ProductSource.AI

    override suspend fun findByGtin(gtin: Gtin): ProductLookupResult? = withContext(Dispatchers.IO) {
        val raw = readCard(gtin) ?: return@withContext null
        if (raw.name.isNullOrBlank() || raw.brand.isNullOrBlank()) {
            log.info(
                "[PRODUCT_AI] read_empty gtin={} — model nie zna tego kodu (name='{}', brand='{}', confidence={}); oddaję null",
                gtin.value, raw.name, raw.brand, raw.confidence
            )
            return@withContext null
        }

        val declared = raw.confidence?.coerceIn(0.0, 1.0) ?: 0.0
        val verdict = verify(gtin, raw)
        // Weryfikator zewnętrzny może tylko OBNIŻYĆ zaufanie, nigdy go podnieść:
        // „nie jestem pewny" znaczy „na pewno nie ≥90%".
        val confidence = if (verdict?.matches == true) declared else minOf(declared, 0.5)
        log.info(
            "[PRODUCT_AI] result gtin={} declared={} verifierMatches={} finalConfidence={} name='{}' brand='{}'",
            gtin.value, declared, verdict?.matches, confidence, raw.name, raw.brand
        )

        val unit = UnitOfMeasure.fromCode(raw.unitOfMeasure) ?: UnitOfMeasure.PIECE
        val sizeUnit = UnitOfMeasure.fromCode(raw.packageSizeUnit) ?: unit
        val spec = ProductSpec(
            gtin = gtin.value,
            name = raw.name.trim(),
            brand = raw.brand.trim(),
            unitOfMeasure = unit,
            packageSizeValue = raw.packageSizeValue?.let { runCatching { BigDecimal(it) }.getOrNull() }
                ?.takeIf { it > BigDecimal.ZERO } ?: BigDecimal.ONE,
            packageSizeUnit = sizeUnit,
            dimensions = PackageDimensions.EMPTY,
            description = raw.description?.trim()?.ifBlank { null },
            imageFileId = null
        )

        val payload = runCatching {
            objectMapper.writeValueAsString(mapOf("read" to raw, "verdict" to verdict))
        }.getOrNull()

        ProductLookupResult(spec = spec, source = ProductSource.AI, confidence = confidence, rawPayload = payload)
    }

    private fun readCard(gtin: Gtin): RawCard? {
        val converter = BeanOutputConverter(RawCard::class.java)
        val userPrompt = "Kod kreskowy (GTIN-14): ${gtin.value}\n\n${converter.format}"
        log.info(
            "[PRODUCT_AI] read_request gtin={}\n--- SYSTEM ---\n{}\n--- USER ---\n{}",
            gtin.value, READ_SYSTEM_PROMPT, userPrompt
        )
        return try {
            val raw = lookupClient.prompt()
                .system(READ_SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .content()
            log.info("[PRODUCT_AI] read_response gtin={}\n--- RAW ---\n{}", gtin.value, raw)
            if (raw.isNullOrBlank()) {
                log.warn("[PRODUCT_AI] read_blank gtin={} — model zwrócił pustą treść", gtin.value)
                return null
            }
            val card = converter.convert(raw)
            log.info("[PRODUCT_AI] read_parsed gtin={} card={}", gtin.value, card)
            card
        } catch (e: Exception) {
            // Pełny stack — „nie powiodło się" bez przyczyny nic nie mówi o 401/timeout/JSON.
            log.warn("[PRODUCT_AI] read_failed gtin={}: {}", gtin.value, e.toString(), e)
            null
        }
    }

    private fun verify(gtin: Gtin, card: RawCard): Verdict? {
        val converter = BeanOutputConverter(Verdict::class.java)
        val userPrompt = """
            GTIN: ${gtin.value}
            Proponowana karta:
            - marka: ${card.brand}
            - nazwa: ${card.name}
            - opakowanie: ${card.packageSizeValue ?: "?"} ${card.packageSizeUnit ?: ""}

            ${converter.format}
        """.trimIndent()
        log.info(
            "[PRODUCT_AI] verify_request gtin={}\n--- SYSTEM ---\n{}\n--- USER ---\n{}",
            gtin.value, VERIFY_SYSTEM_PROMPT, userPrompt
        )
        return try {
            val raw = verifierClient.prompt()
                .system(VERIFY_SYSTEM_PROMPT)
                .user(userPrompt)
                .call()
                .content()
            log.info("[PRODUCT_AI] verify_response gtin={}\n--- RAW ---\n{}", gtin.value, raw)
            if (raw.isNullOrBlank()) return null
            val verdict = converter.convert(raw)
            log.info("[PRODUCT_AI] verify_parsed gtin={} verdict={}", gtin.value, verdict)
            verdict
        } catch (e: Exception) {
            log.warn("[PRODUCT_AI] verify_failed gtin={}: {}", gtin.value, e.toString(), e)
            null
        }
    }

    /** Surowy odczyt modelu. Structured output gwarantuje kształt. */
    internal data class RawCard(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("brand") val brand: String? = null,
        @JsonProperty("unitOfMeasure") val unitOfMeasure: String? = null,
        @JsonProperty("packageSizeValue") val packageSizeValue: String? = null,
        @JsonProperty("packageSizeUnit") val packageSizeUnit: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("confidence") val confidence: Double? = null
    )

    internal data class Verdict(
        @JsonProperty("matches") val matches: Boolean = false,
        @JsonProperty("reason") val reason: String? = null
    )

    companion object {
        private val READ_SYSTEM_PROMPT = """
Jesteś narzędziem służącym do znajdowania danych o produktach na podstawie ich GTIN/EAN. 
Zwróć dane w ustalonej strukturze.

ZASADY:
- Jednostki: ML, L, G, KG, PIECE, PAIR, M, M2.
- packageSizeValue to LICZBA (np. "500"), packageSizeUnit to jednostka tej liczby.
- confidence: 0.0–1.0, TWOJA szczera pewność, że karta należy do TEGO kodu.
""".trim()

        private val VERIFY_SYSTEM_PROMPT = """
Jesteś niezależnym recenzentem. Dostajesz kod kreskowy i proponowaną kartę produktu.
Twoje jedyne zadanie: ocenić, czy naprawdę istnieje dowolny dowód na to, że produkt został poprawnie rozpoznany na podstawie GTIN/EAN. }.
""".trim()
    }
}
