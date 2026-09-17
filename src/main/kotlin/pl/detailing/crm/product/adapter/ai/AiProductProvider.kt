package pl.detailing.crm.product.adapter.ai

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
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
 *      spada do 0 i łańcuch schodzi do GS1 — niezależnie od tego, jak pewny był
 *      pierwszy model.
 *
 * Do modelu NIE trafia nic poza kodem: ani nazwa studia, ani klient, ani wizyta.
 * To wymóg bezpieczeństwa, nie oszczędność tokenów.
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
        if (raw.name.isNullOrBlank() || raw.brand.isNullOrBlank()) return@withContext null

        val declared = raw.confidence?.coerceIn(0.0, 1.0) ?: 0.0
        val verdict = verify(gtin, raw)
        // Weryfikator zewnętrzny może tylko OBNIŻYĆ zaufanie, nigdy go podnieść:
        // „nie jestem pewny" znaczy „na pewno nie ≥90%".
        val confidence = if (verdict?.matches == true) declared else minOf(declared, 0.5)

        val unit = UnitOfMeasure.fromCode(raw.unitOfMeasure) ?: UnitOfMeasure.PIECE
        val sizeUnit = UnitOfMeasure.fromCode(raw.packageSizeUnit) ?: unit
        val spec = ProductSpec(
            gtin = gtin.value,
            name = raw.name.trim(),
            brand = raw.brand.trim(),
            manufacturerName = raw.manufacturerName?.trim()?.ifBlank { null } ?: raw.brand.trim(),
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

    private fun readCard(gtin: Gtin): RawCard? = try {
        lookupClient.prompt()
            .system(READ_SYSTEM_PROMPT)
            .user("Kod kreskowy (GTIN-14): ${gtin.value}")
            .call()
            .entity(RawCard::class.java)
    } catch (e: Exception) {
        log.warn("[PRODUCT_AI] Odczyt karty po GTIN {} nie powiódł się: {}", gtin, e.message)
        null
    }

    private fun verify(gtin: Gtin, card: RawCard): Verdict? = try {
        verifierClient.prompt()
            .system(VERIFY_SYSTEM_PROMPT)
            .user(
                """
                GTIN: ${gtin.value}
                Proponowana karta:
                - marka: ${card.brand}
                - nazwa: ${card.name}
                - producent: ${card.manufacturerName ?: "?"}
                - opakowanie: ${card.packageSizeValue ?: "?"} ${card.packageSizeUnit ?: ""}
                """.trimIndent()
            )
            .call()
            .entity(Verdict::class.java)
    } catch (e: Exception) {
        log.warn("[PRODUCT_AI] Weryfikacja GTIN {} nie powiodła się: {}", gtin, e.message)
        null
    }

    /** Surowy odczyt modelu. Structured output gwarantuje kształt. */
    internal data class RawCard(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("brand") val brand: String? = null,
        @JsonProperty("manufacturerName") val manufacturerName: String? = null,
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
Rozpoznajesz produkt chemii i akcesoriów detailingowych po kodzie kreskowym (GTIN).
Zwróć dane w ustalonej strukturze.

ZASADY:
- Jednostki: ML, L, G, KG, PIECE, PAIR, M, M2.
- packageSizeValue to LICZBA (np. "500"), packageSizeUnit to jednostka tej liczby.
- confidence: 0.0–1.0, TWOJA szczera pewność, że karta należy do TEGO kodu.
- NIE ZGADUJ. Jeśli nie znasz tego kodu, ustaw confidence: 0 i zostaw pola puste.
  Zmyślona, wiarygodnie brzmiąca karta jest gorsza niż jej brak — zatruwa katalog
  współdzielony przez wszystkie warsztaty.
""".trim()

        private val VERIFY_SYSTEM_PROMPT = """
Jesteś niezależnym recenzentem. Dostajesz kod kreskowy i proponowaną kartę produktu.
Twoje jedyne zadanie: ocenić, czy naprawdę jesteś pewny, że ta karta należy do tego
kodu. Bądź surowy — jeśli masz jakąkolwiek wątpliwość, odpowiedz matches: false.
Zwróć { "matches": true/false, "reason": "krótko dlaczego" }.
""".trim()
    }
}
