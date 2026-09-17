package pl.detailing.crm.product.adapter.web

import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.converter.BeanOutputConverter
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Rozpoznanie kodu przez model, KTÓRY NAPRAWDĘ SZUKA W SIECI (`web_search_options`).
 *
 * To jest odpowiedź na „czy jest model z trybem research": tak — i nie trzeba do niego
 * osobnego klucza do wyszukiwarki ani nowego dostawcy. Ten sam klucz OpenAI, którego
 * używa reszta systemu; różnica jest w modelu (rodzina `*-search-preview`) i w tym, że
 * żądanie niesie `web_search_options`, więc model przed odpowiedzią wykonuje realne
 * wyszukiwanie.
 *
 * Domyślnie WYŁĄCZONY: wyszukiwanie jest droższe od zwykłego wywołania i to decyzja
 * kosztowa właściciela instalacji, nie domyślna.
 *
 * Format wymuszamy instrukcją w treści promptu ([BeanOutputConverter.getFormat]), a nie
 * `response_format` — modele wyszukujące nie gwarantują structured output, a instrukcja
 * tekstowa działa wszędzie. Surowa odpowiedź trafia do logu przed parsowaniem.
 */
@Component
class OpenAiWebSearchClient(
    @Qualifier("productWebSearchChatClient") private val chatClient: ChatClient,
    @Value("\${crm.products.web.openai-search.enabled:false}") val enabled: Boolean
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun lookup(ean: String): WebProductCard? {
        if (!enabled) return null
        val converter = BeanOutputConverter(SearchedCard::class.java)
        val userPrompt = """
            Wyszukaj w internecie produkt o kodzie kreskowym EAN: $ean

            Podaj markę, nazwę produktu i pojemność/wagę opakowania na podstawie ZNALEZIONYCH
            ofert i kart produktu. Jeśli wyszukiwanie nie wskazuje jednoznacznie jednego
            produktu, zostaw pola puste i ustaw confidence 0.0.

            ${converter.format}
        """.trimIndent()

        log.info("[PRODUCT_WEB] openai_search_request ean={}\n--- SYSTEM ---\n{}\n--- USER ---\n{}", ean, SYSTEM_PROMPT, userPrompt)
        return try {
            val raw = chatClient.prompt().system(SYSTEM_PROMPT).user(userPrompt).call().content()
            log.info("[PRODUCT_WEB] openai_search_response ean={}\n--- RAW ---\n{}", ean, raw)
            if (raw.isNullOrBlank()) return null
            // Model wyszukujący lubi dokleić przypisy źródeł poza JSON-em — bierzemy
            // największy blok {...}, zamiast wywracać się na parsowaniu całości.
            val json = raw.substringAfter('{', "").substringBeforeLast('}', "").let { if (it.isBlank()) null else "{$it}" }
                ?: raw
            val card = converter.convert(json)
            log.info("[PRODUCT_WEB] openai_search_parsed ean={} card={}", ean, card)
            if (card == null || card.name.isNullOrBlank() || card.brand.isNullOrBlank()) return null
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
            log.warn("[PRODUCT_WEB] openai_search_failed ean={}: {}", ean, e.toString(), e)
            null
        }
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
  NIE zgaduj i nie uzupełniaj z własnej wiedzy.
""".trim()
    }
}
