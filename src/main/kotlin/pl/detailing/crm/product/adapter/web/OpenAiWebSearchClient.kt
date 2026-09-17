package pl.detailing.crm.product.adapter.web

import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.converter.BeanOutputConverter
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Rozpoznanie produktu po kodzie przez model, KTÓRY NAPRAWDĘ SZUKA W SIECI.
 *
 * Model pytany z samej pamięci nie potrafi zmapować EAN-u na produkt — kod kreskowy to
 * numer nadany przez GS1, nazwa produktu nie jest z niego wyprowadzalna, więc dla realnego
 * kodu wracało `confidence: 0.0` i puste pola. Wyszukiwarka ten sam kod rozpoznaje bez
 * problemu, bo indeksuje strony sklepów. Dlatego jedyne zewnętrzne źródło w tym module to
 * model z `web_search_options`.
 *
 * Format wymuszamy instrukcją w treści promptu ([BeanOutputConverter.getFormat]), a nie
 * `response_format` — modele wyszukujące nie gwarantują structured output, a instrukcja
 * tekstowa działa wszędzie. Surowa odpowiedź trafia do logu PRZED parsowaniem: bez tego
 * „NOT_FOUND" z produkcji jest nie do zdiagnozowania.
 */
@Component
class OpenAiWebSearchClient(
    @Qualifier("productWebSearchChatClient") private val chatClient: ChatClient,
    @Value("\${crm.products.web.search.enabled:true}") val enabled: Boolean,
    @Value("\${crm.products.web.search.model:gpt-4o-mini-search-preview}") private val model: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Do sygnatury negatywnego cache — zmiana modelu musi unieważnić stare „miss". */
    val signature: String get() = if (enabled) model else "off"

    fun lookup(ean: String): WebProductCard? {
        if (!enabled) {
            log.info("[PRODUCT_WEB] disabled ean={} — wyszukiwanie w sieci jest wyłączone", ean)
            return null
        }
        val converter = BeanOutputConverter(SearchedCard::class.java)
        val userPrompt = """
            Wyszukaj w internecie produkt o kodzie kreskowym EAN: $ean

            Podaj markę, nazwę produktu i pojemność/wagę opakowania na podstawie ZNALEZIONYCH
            ofert i kart produktu. Jeśli wyszukiwanie nie wskazuje jednoznacznie jednego
            produktu, zostaw pola puste i ustaw confidence 0.0.

            ${converter.format}
        """.trimIndent()

        log.info("[PRODUCT_WEB] request ean={} model={}\n--- SYSTEM ---\n{}\n--- USER ---\n{}", ean, model, SYSTEM_PROMPT, userPrompt)
        return try {
            val raw = chatClient.prompt().system(SYSTEM_PROMPT).user(userPrompt).call().content()
            log.info("[PRODUCT_WEB] response ean={}\n--- RAW ---\n{}", ean, raw)
            if (raw.isNullOrBlank()) return null

            val card = converter.convert(extractJson(raw))
            log.info("[PRODUCT_WEB] parsed ean={} card={}", ean, card)
            if (card == null || card.name.isNullOrBlank() || card.brand.isNullOrBlank()) {
                log.info("[PRODUCT_WEB] empty ean={} — wyszukiwanie nie wskazało produktu", ean)
                return null
            }
            WebProductCard(
                brand = card.brand.trim(),
                name = card.name.trim(),
                packageSizeValue = card.packageSizeValue?.trim()?.ifBlank { null },
                packageSizeUnit = card.packageSizeUnit?.trim()?.ifBlank { null },
                description = card.description?.trim()?.ifBlank { null },
                sourceUrl = card.sourceUrl?.trim()?.ifBlank { null },
                confidence = card.confidence?.coerceIn(0.0, 1.0) ?: 0.0
            )
        } catch (e: Exception) {
            // Pełny stack — „nie powiodło się" bez przyczyny nic nie mówi o 401/timeout/JSON.
            log.warn("[PRODUCT_WEB] failed ean={}: {}", ean, e.toString(), e)
            null
        }
    }

    /**
     * Model wyszukujący lubi dokleić przypisy źródeł poza JSON-em. Bierzemy blok od
     * pierwszej `{` do ostatniej `}` zamiast wywracać się na parsowaniu całości.
     */
    private fun extractJson(raw: String): String {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        return if (start >= 0 && end > start) raw.substring(start, end + 1) else raw
    }

    internal data class SearchedCard(
        @JsonProperty("brand") val brand: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("packageSizeValue") val packageSizeValue: String? = null,
        @JsonProperty("packageSizeUnit") val packageSizeUnit: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("sourceUrl") val sourceUrl: String? = null,
        @JsonProperty("confidence") val confidence: Double? = null
    )

    companion object {
        private val SYSTEM_PROMPT = """
Identyfikujesz produkt po kodzie kreskowym, korzystając z WYSZUKIWANIA W INTERNECIE.
Opierasz się na tym, co znajdziesz — nie na pamięci o kodzie.

ZASADY:
- "brand": marka/producent (np. "ADBL", "Gyeon", "Koch Chemie").
- "name": nazwa produktu BEZ marki i BEZ pojemności.
- "packageSizeValue"/"packageSizeUnit": pojemność lub waga, jeśli wynika z ofert
  (np. "500" + "ML"). Dozwolone jednostki: ML, L, G, KG. Gdy nie wynika — puste.
- "sourceUrl": adres najlepszego znalezionego źródła.
- "confidence": 0.0–1.0. Wysoka (0.85+) tylko wtedy, gdy kilka niezależnych źródeł
  zgodnie przypisuje ten kod do tego samego produktu.
- Jeśli wyszukiwanie nie wskazuje konkretnego produktu — puste pola i confidence 0.0.
  NIE zgaduj i nie uzupełniaj z własnej wiedzy. Zmyślona, wiarygodnie brzmiąca karta
  jest gorsza niż jej brak: katalog jest współdzielony przez wszystkie warsztaty.
""".trim()
    }
}
