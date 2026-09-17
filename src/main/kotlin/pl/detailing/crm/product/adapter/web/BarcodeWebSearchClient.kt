package pl.detailing.crm.product.adapter.web

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Wyszukiwarka jako źródło danych o kodzie — dokładnie to, co robi człowiek, gdy wkleja
 * EAN w Google i widzi „ADBL Zestaw kosmetyków…".
 *
 * WYMAGA KLUCZA, więc domyślnie jest WYŁĄCZONA (`provider=NONE`) i moduł działa bez niej.
 * Obsługiwane backendy:
 *  - GOOGLE — Programmable Search Engine (JSON API): `key` + `cx`, darmowy próg 100/dzień,
 *  - BRAVE  — Brave Search API: nagłówek `X-Subscription-Token`, darmowy próg miesięczny.
 *
 * Zwracamy wyłącznie TYTUŁY i FRAGMENTY z wyników — nie pobieramy stron sklepów. To
 * świadome ograniczenie: tytuł listingu („ADBL Q2M Bathe szampon 500 ml") niesie komplet
 * potrzebnych danych, a nie wchodzimy w scraping cudzych serwisów.
 */
@Component
class BarcodeWebSearchClient(
    private val objectMapper: ObjectMapper,
    private val webLookupRestTemplate: RestTemplate,
    @Value("\${crm.products.web.search.provider:NONE}") private val providerRaw: String,
    @Value("\${crm.products.web.search.google.key:}") private val googleKey: String,
    @Value("\${crm.products.web.search.google.cx:}") private val googleCx: String,
    @Value("\${crm.products.web.search.brave.key:}") private val braveKey: String,
    @Value("\${crm.products.web.search.max-results:8}") private val maxResults: Int
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val provider: String get() = providerRaw.trim().uppercase()

    val enabled: Boolean
        get() = when (provider) {
            "GOOGLE" -> googleKey.isNotBlank() && googleCx.isNotBlank()
            "BRAVE" -> braveKey.isNotBlank()
            else -> false
        }

    /** Nazwa backendu do logów i do sygnatury negatywnego cache. */
    val providerName: String get() = if (enabled) provider else "NONE"

    fun search(ean: String): List<WebSnippet> {
        if (!enabled) return emptyList()
        val q = URLEncoder.encode(ean, StandardCharsets.UTF_8)
        return try {
            val snippets = when (provider) {
                "GOOGLE" -> google(q)
                "BRAVE" -> brave(q)
                else -> emptyList()
            }
            log.info("[PRODUCT_WEB] search provider={} ean={} results={}", provider, ean, snippets.size)
            snippets
        } catch (e: Exception) {
            log.warn("[PRODUCT_WEB] search_failed provider={} ean={}: {}", provider, ean, e.toString())
            emptyList()
        }
    }

    private fun google(q: String): List<WebSnippet> {
        val url = "https://www.googleapis.com/customsearch/v1?key=$googleKey&cx=$googleCx&num=$maxResults&q=$q"
        val body = webLookupRestTemplate.getForObject(url, String::class.java) ?: return emptyList()
        return objectMapper.readTree(body).path("items").take(maxResults).map {
            WebSnippet(
                title = it.path("title").asText(""),
                snippet = it.path("snippet").asText(""),
                url = it.path("link").asText("")
            )
        }
    }

    private fun brave(q: String): List<WebSnippet> {
        val headers = HttpHeaders().apply {
            set("X-Subscription-Token", braveKey)
            set("Accept", "application/json")
        }
        val url = "https://api.search.brave.com/res/v1/web/search?count=$maxResults&q=$q"
        val body = webLookupRestTemplate
            .exchange(url, HttpMethod.GET, HttpEntity<Void>(headers), String::class.java).body
            ?: return emptyList()
        return objectMapper.readTree(body).path("web").path("results").take(maxResults).map {
            WebSnippet(
                title = it.path("title").asText(""),
                snippet = it.path("description").asText(""),
                url = it.path("url").asText("")
            )
        }
    }
}
