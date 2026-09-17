package pl.detailing.crm.product.adapter.web

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.ai.converter.BeanOutputConverter
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate

/**
 * Rozpoznanie produktu po kodzie przez hostowane narzędzie `web_search` w Responses API.
 *
 * DLACZEGO WPROST PO HTTP, A NIE PRZEZ SPRING AI. Spring AI 1.0.0 obsługuje tylko Chat
 * Completions, gdzie wyszukiwanie było dostępne wyłącznie przez modele `*-search-preview`.
 * OpenAI je wycofało (shutdown 2026-07-23) — `gpt-4o-mini-search-preview` zwracał już
 * 404 `model_not_found`. Hostowane narzędzie `web_search` żyje w Responses API, więc
 * wołamy je bezpośrednio. Model NIE musi być specjalny: dokumentacja wymienia `gpt-4.1`
 * jako wspierany, a ten jest już używany w tym systemie — czyli nie polegamy na nazwie
 * modelu o krótkim życiu, tylko na narzędziu.
 *
 * `tool_choice = "required"` jest ISTOTNE. Przy `auto` wyszukiwanie jest OPCJONALNE i
 * model może odpowiedzieć z pamięci — a z pamięci nie potrafi zmapować EAN-u na produkt
 * i oddaje pustkę. `required` wymusza realne wyszukiwanie przed odpowiedzią.
 *
 * Do modelu trafia wyłącznie kod — nigdy nazwa studia, klienta ani kontekst wizyty.
 */
@Component
class OpenAiWebSearchClient(
    @Qualifier("openAiResponsesRestTemplate") private val restTemplate: RestTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${crm.products.web.search.enabled:true}") val enabled: Boolean,
    @Value("\${crm.products.web.search.model:gpt-4.1}") private val model: String,
    @Value("\${crm.products.web.search.context-size:medium}") private val contextSize: String,
    @Value("\${crm.products.web.search.country:PL}") private val country: String,
    @Value("\${spring.ai.openai.api-key:}") private val apiKey: String,
    @Value("\${spring.ai.openai.base-url:https://api.openai.com}") private val baseUrl: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Do sygnatury negatywnego cache — zmiana modelu musi unieważnić stare „miss". */
    val signature: String get() = if (enabled) model else "off"

    fun lookup(ean: String): WebProductCard? {
        if (!enabled) {
            log.info("[PRODUCT_WEB] disabled ean={} — wyszukiwanie w sieci jest wyłączone", ean)
            return null
        }
        if (apiKey.isBlank()) {
            log.warn("[PRODUCT_WEB] no_api_key ean={} — brak spring.ai.openai.api-key, pomijam wyszukiwanie", ean)
            return null
        }

        val converter = BeanOutputConverter(SearchedCard::class.java)
        val prompt = """
            $SYSTEM_PROMPT

            Kod kreskowy EAN: $ean

            ${converter.format}
        """.trimIndent()

        val body = mapOf(
            "model" to model,
            "tools" to listOf(
                mapOf(
                    "type" to "web_search",
                    "search_context_size" to contextSize,
                    // Kraj jako dwuliterowy kod ISO — oferty tego samego kodu są lokalne.
                    "user_location" to mapOf("type" to "approximate", "country" to country)
                )
            ),
            // Wyszukiwanie MUSI się wykonać — patrz komentarz klasy.
            "tool_choice" to "required",
            "input" to prompt
        )

        return try {
            val json = objectMapper.writeValueAsString(body)
            log.info("[PRODUCT_WEB] request ean={} model={}\n--- BODY ---\n{}", ean, model, json)

            val headers = HttpHeaders().apply {
                contentType = MediaType.APPLICATION_JSON
                setBearerAuth(apiKey)
            }
            val raw = restTemplate.postForObject(
                "${baseUrl.trimEnd('/')}/v1/responses", HttpEntity(json, headers), String::class.java
            )
            log.info("[PRODUCT_WEB] response ean={}\n--- RAW ---\n{}", ean, raw)
            if (raw.isNullOrBlank()) return null

            val root = objectMapper.readTree(raw)
            val text = extractOutputText(root)
            val citation = extractFirstCitation(root)
            log.info("[PRODUCT_WEB] extracted ean={} citation={} text='{}'", ean, citation, text)
            if (text.isBlank()) return null

            val card = converter.convert(extractJson(text))
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
                // Cytowanie z adnotacji jest wiarygodniejsze niż URL przepisany przez model.
                sourceUrl = citation ?: card.sourceUrl?.trim()?.ifBlank { null },
                confidence = card.confidence?.coerceIn(0.0, 1.0) ?: 0.0
            )
        } catch (e: Exception) {
            // Pełny stack — „nie powiodło się" bez przyczyny nic nie mówi o 401/404/timeout.
            log.warn("[PRODUCT_WEB] failed ean={}: {}", ean, e.toString(), e)
            null
        }
    }

    /**
     * Odpowiedź Responses API to LISTA pozycji: `web_search_call` (ślad wyszukiwania) i
     * `message` z treścią. Tekst siedzi w `message.content[].text` dla typu `output_text`.
     */
    private fun extractOutputText(root: JsonNode): String =
        root.path("output")
            .filter { it.path("type").asText() == "message" }
            .flatMap { it.path("content").toList() }
            .filter { it.path("type").asText() == "output_text" }
            .joinToString("\n") { it.path("text").asText("") }
            .trim()

    /**
     * Pierwsze `url_citation` z adnotacji. Dokumentacja OpenAI wymaga, żeby źródła
     * pokazane użytkownikowi były widoczne i klikalne — dlatego niesiemy je dalej aż do
     * formularza, a nie tylko do logu.
     */
    private fun extractFirstCitation(root: JsonNode): String? =
        root.path("output")
            .filter { it.path("type").asText() == "message" }
            .flatMap { it.path("content").toList() }
            .flatMap { it.path("annotations").toList() }
            .firstOrNull { it.path("type").asText() == "url_citation" }
            ?.path("url")?.asText(null)?.ifBlank { null }

    /** Model lubi dokleić przypisy poza JSON-em — bierzemy blok od `{` do ostatniej `}`. */
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

Odpowiedz wyłącznie obiektem JSON opisanym niżej.
""".trim()
    }
}
