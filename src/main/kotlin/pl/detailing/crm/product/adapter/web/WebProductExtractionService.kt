package pl.detailing.crm.product.adapter.web

import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.converter.BeanOutputConverter
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

/**
 * Wyciąga kartę produktu z WYNIKÓW WYSZUKIWANIA. To jest właściwa rola modelu w tym
 * module: nie „przypomnij sobie, co kryje ten kod" (czego model nie potrafi i nie
 * powinien udawać), tylko „masz tu prawdziwe tytuły ofert, wyciągnij z nich markę,
 * nazwę i pojemność". Ekstrakcja z podanego tekstu to zadanie, w którym model jest
 * mocny i sprawdzalny — fragmenty wędrują do logu razem z odpowiedzią.
 *
 * Do modelu trafiają wyłącznie kod i publiczne fragmenty wyników — żadnych danych
 * studia, klienta ani wizyty.
 */
@Service
class WebProductExtractionService(
    @Qualifier("productLookupChatClient") private val chatClient: ChatClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun extract(ean: String, snippets: List<WebSnippet>): WebProductCard? {
        if (snippets.isEmpty()) return null
        val converter = BeanOutputConverter(ExtractedCard::class.java)
        val listing = snippets.mapIndexed { i, s ->
            "${i + 1}. ${s.title}\n   ${s.snippet}\n   ${s.url}"
        }.joinToString("\n")
        val userPrompt = """
            Kod kreskowy: $ean

            Wyniki wyszukiwania dla tego kodu:
            $listing

            ${converter.format}
        """.trimIndent()

        log.info("[PRODUCT_WEB] extract_request ean={}\n--- SYSTEM ---\n{}\n--- USER ---\n{}", ean, SYSTEM_PROMPT, userPrompt)
        return try {
            val raw = chatClient.prompt().system(SYSTEM_PROMPT).user(userPrompt).call().content()
            log.info("[PRODUCT_WEB] extract_response ean={}\n--- RAW ---\n{}", ean, raw)
            if (raw.isNullOrBlank()) return null
            val card = converter.convert(raw)
            log.info("[PRODUCT_WEB] extract_parsed ean={} card={}", ean, card)
            if (card == null || card.name.isNullOrBlank() || card.brand.isNullOrBlank()) return null
            WebProductCard(
                brand = card.brand.trim(),
                name = card.name.trim(),
                packageSizeValue = card.packageSizeValue?.trim()?.ifBlank { null },
                packageSizeUnit = card.packageSizeUnit?.trim()?.ifBlank { null },
                description = card.description?.trim()?.ifBlank { null },
                sourceUrl = snippets.firstOrNull()?.url,
                confidence = card.confidence?.coerceIn(0.0, 1.0) ?: 0.0
            )
        } catch (e: Exception) {
            log.warn("[PRODUCT_WEB] extract_failed ean={}: {}", ean, e.toString(), e)
            null
        }
    }

    internal data class ExtractedCard(
        @JsonProperty("brand") val brand: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("packageSizeValue") val packageSizeValue: String? = null,
        @JsonProperty("packageSizeUnit") val packageSizeUnit: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("confidence") val confidence: Double? = null
    )

    companion object {
        private val SYSTEM_PROMPT = """
Dostajesz kod kreskowy (EAN) i wyniki wyszukiwania dla tego kodu — tytuły i opisy ofert
sklepowych. Twoim zadaniem jest WYDOBYĆ z nich kartę produktu. Nie korzystasz z własnej
pamięci o kodzie: opierasz się WYŁĄCZNIE na podanych fragmentach.

ZASADY:
- "brand": marka/producent (np. "ADBL", "Gyeon", "Koch Chemie").
- "name": nazwa produktu BEZ marki i BEZ pojemności (np. "Q2M Bathe", "Zestaw do wnętrza").
- "packageSizeValue"/"packageSizeUnit": pojemność lub waga, jeśli wynika z wyników
  (np. "500" + "ML"). Dozwolone jednostki: ML, L, G, KG. Gdy nie wynika — zostaw puste.
- "description": jedno zdanie, co to za produkt.
- "confidence": 0.0–1.0. Wysoka (0.85+) TYLKO gdy kilka niezależnych wyników zgodnie
  wskazuje ten sam produkt. Gdy wyniki są sprzeczne, dotyczą listy produktów albo nie
  widać konkretnego towaru — daj niską wartość.
- Jeśli wyniki nie pozwalają wskazać konkretnego produktu, zostaw "brand" i "name" puste
  i ustaw confidence 0.0. NIE zgaduj i nie uzupełniaj z własnej wiedzy.
""".trim()
    }
}
