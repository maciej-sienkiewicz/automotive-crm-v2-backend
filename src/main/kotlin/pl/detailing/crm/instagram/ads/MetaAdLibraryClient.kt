package pl.detailing.crm.instagram.ads

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.LocalDate

/**
 * Klient Biblioteki reklam Meta (Graph API, endpoint `ads_archive`).
 *
 * Trzy rzeczy, które decydują o tym, czy w ogóle coś zwróci:
 *
 * 1. **Kraj.** Reklamy KOMERCYJNE (a nie tylko polityczne) biblioteka udostępnia
 *    wyłącznie dla krajów UE i Wielkiej Brytanii. `ad_reached_countries=PL`
 *    jest więc warunkiem koniecznym, nie filtrem wygody.
 * 2. **Weryfikacja tożsamości.** Token bez przejścia onboardingu dostaje błąd
 *    code 10 / subcode 2332002. To nie jest problem uprawnień — uprawnienia
 *    Marketing API (`ads_read`) tu nie pomagają.
 * 3. **Token systemowy.** Token z Eksploratora API wygasa po 1–2 godzinach,
 *    więc do harmonogramu nadaje się wyłącznie token użytkownika systemowego.
 *
 * Bez skonfigurowanego tokena klient jest wyłączony i zwraca puste wyniki —
 * cała funkcja działa wtedy w trybie „brak danych", zamiast wysypywać sync.
 */
@Component
class MetaAdLibraryClient(
    private val objectMapper: ObjectMapper,
    private val callGate: MetaAdsCallGate,
    @Value("\${meta.ads.token:}") private val accessToken: String,
    @Value("\${meta.ads.api-version:v26.0}") private val apiVersion: String,
    @Value("\${meta.ads.timeout-seconds:30}") private val timeoutSeconds: Long,
    @Value("\${meta.ads.page-size:200}") private val pageSize: Int
) {
    private val log = LoggerFactory.getLogger(MetaAdLibraryClient::class.java)

    companion object {
        /** Twardy limit Meta: jedno zapytanie obsłuży najwyżej tyle stron. */
        const val MAX_PAGES_PER_CALL = 10

        /** Biblioteka trzyma reklamy rok wstecz — starszych nie ma sensu szukać. */
        const val RETENTION_DAYS = 365L

        private const val FIELDS =
            "id,page_id,page_name,ad_creative_link_titles,ad_delivery_start_time,ad_delivery_stop_time," +
                "eu_total_reach,age_country_gender_reach_breakdown,target_ages,target_gender," +
                "target_locations,beneficiary_payers,publisher_platforms,ad_snapshot_url"

        /** Ile stron paginacji maksymalnie przejdziemy — zapora przed pętlą kursorów. */
        private const val MAX_PAGES = 20

        /** Krótsza fraza to wyszukiwanie połowy biblioteki — i tak nikt by tego nie przeczytał. */
        private const val MIN_SEARCH_LENGTH = 3

        /** Jedno wywołanie na wyszukanie: bierzemy szeroko i grupujemy po stronie u siebie. */
        private const val SEARCH_PAGE_SIZE = 300

        /** Tyle kandydatów da się przejrzeć wzrokiem; więcej znaczy „doprecyzuj frazę". */
        private const val MAX_SEARCH_RESULTS = 12
    }

    /** Token jest jedyną rzeczą do podmiany w dniu aktywacji konta. */
    val enabled: Boolean get() = accessToken.isNotBlank()

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(timeoutSeconds))
        .build()

    /**
     * Reklamy wszystkich podanych stron. Dzieli na paczki po [MAX_PAGES_PER_CALL],
     * bo tyle przyjmuje `search_page_ids` w jednym wywołaniu.
     */
    fun fetchAdsForPages(pageIds: List<String>): List<RawMetaAd> {
        if (!enabled || pageIds.isEmpty()) return emptyList()

        return pageIds.distinct()
            .chunked(MAX_PAGES_PER_CALL)
            .flatMap { batch -> fetchBatch(batch) }
    }

    private fun fetchBatch(pageIds: List<String>): List<RawMetaAd> {
        val ads = mutableListOf<RawMetaAd>()
        var after: String? = null
        var page = 0

        do {
            val body = callGate.call("ads_archive") { get(buildUrl(pageIds, after)) }
            val root = objectMapper.readTree(body)

            root.path("data").forEach { node ->
                MetaAdParser.parseAd(node)?.let { ads += it }
            }

            after = root.path("paging").path("cursors").path("after").textOrNull()
            page++
        } while (after != null && root.path("data").size() > 0 && page < MAX_PAGES)

        return ads
    }

    /**
     * Strony reklamodawców pasujące do frazy — po to, żeby nikt nie musiał
     * polować na numeryczny identyfikator strony.
     *
     * Biblioteka reklam pokazuje w panelu ALBO numer strony, ALBO jej nazwę
     * użytkownika (@carartdetailing) — nigdy obu naraz. Kto trafi na tę drugą
     * postać, nie ma skąd wziąć numeru, a `search_page_ids` przyjmuje wyłącznie
     * numer. Za to KAŻDA odpowiedź z `ads_archive` niesie `page_id` obok
     * `page_name`, więc wystarczy zapytać o cokolwiek tej strony i odczytać numer.
     *
     * Ograniczenie wynikające z samej biblioteki: znajdziemy tylko te strony,
     * które w ostatnim roku cokolwiek reklamowały. Dla nas to nie jest strata —
     * strona, która się nie reklamuje, nie ma czego pokazać w kalendarzu.
     */
    fun searchPages(term: String): List<MetaPageCandidate> {
        if (!enabled) return emptyList()
        val query = term.trim().takeIf { it.length >= MIN_SEARCH_LENGTH } ?: return emptyList()

        val body = callGate.call("ads_archive_search") { get(buildSearchUrl(query)) }
        val root = objectMapper.readTree(body)

        return root.path("data")
            .mapNotNull { node ->
                val pageId = node.path("page_id").textOrNull() ?: return@mapNotNull null
                val pageName = node.path("page_name").textOrNull()?.trim().orEmpty()
                val start = MetaAdParser.parseDate(node.path("ad_delivery_start_time").textOrNull())
                Triple(pageId, pageName, start)
            }
            .groupBy { it.first }
            .map { (pageId, hits) ->
                MetaPageCandidate(
                    pageId = pageId,
                    pageName = hits.firstNotNullOfOrNull { it.second.takeIf(String::isNotBlank) } ?: pageId,
                    ads = hits.size,
                    lastStart = hits.mapNotNull { it.third }.maxOrNull()
                )
            }
            // Najpierw ci, którzy reklamują się najintensywniej — przy zbieżnych
            // nazwach to zwykle ten, o którego chodzi.
            .sortedWith(compareByDescending<MetaPageCandidate> { it.ads }.thenByDescending { it.lastStart })
            .take(MAX_SEARCH_RESULTS)
    }

    private fun buildSearchUrl(term: String): String {
        val since = LocalDate.now().minusDays(RETENTION_DAYS)
        return buildString {
            append("https://graph.facebook.com/$apiVersion/ads_archive")
            append("?access_token=").append(encode(accessToken))
            append("&ad_reached_countries=").append(encode("[\"PL\"]"))
            append("&search_terms=").append(encode(term))
            append("&ad_type=ALL")
            append("&ad_active_status=ALL")
            append("&ad_delivery_date_min=").append(since)
            append("&fields=").append(encode("page_id,page_name,ad_delivery_start_time"))
            append("&limit=").append(SEARCH_PAGE_SIZE)
        }
    }

    private fun buildUrl(pageIds: List<String>, after: String?): String {
        val pages = pageIds.joinToString(",", "[", "]") { "\"$it\"" }
        val since = LocalDate.now().minusDays(RETENTION_DAYS)
        return buildString {
            append("https://graph.facebook.com/$apiVersion/ads_archive")
            append("?access_token=").append(encode(accessToken))
            append("&ad_reached_countries=").append(encode("[\"PL\"]"))
            append("&search_page_ids=").append(encode(pages))
            append("&ad_type=ALL")
            append("&ad_active_status=ALL")
            append("&ad_delivery_date_min=").append(since)
            append("&fields=").append(encode(FIELDS))
            append("&limit=").append(pageSize.coerceIn(1, 500))
            if (after != null) append("&after=").append(encode(after))
        }
    }

    private fun get(url: String): String {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .GET()
            .build()

        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw MetaAdsException(null, null, null, "Biblioteka reklam Meta nieosiągalna: ${e.message}")
        }

        if (response.statusCode() !in 200..299) {
            throw describeError(response.statusCode(), response.body())
        }
        return response.body()
    }

    /**
     * Błąd 10/2332002 znaczy dokładnie jedno: konto nie przeszło weryfikacji
     * tożsamości w bibliotece reklam. Warto to powiedzieć wprost, bo z samego
     * „(#10) Application does not have permission" nikt tego nie odgadnie.
     */
    private fun describeError(status: Int, body: String): MetaAdsException {
        val error = runCatching { objectMapper.readTree(body).path("error") }.getOrNull()
        val code = error?.path("code")?.asIntOrNull()
        val subcode = error?.path("error_subcode")?.asIntOrNull()
        val apiMessage = error?.path("message")?.textOrNull() ?: body.take(300)

        val message = if (code == 10 && subcode == 2332002) {
            "Biblioteka reklam Meta: konto nie przeszło weryfikacji tożsamości " +
                "(facebook.com/ads/library/api). Do czasu weryfikacji API nie zwraca reklam."
        } else {
            "Biblioteka reklam Meta odrzuciła zapytanie (HTTP $status, code=$code/$subcode): $apiMessage"
        }
        return MetaAdsException(status, code, subcode, message)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun JsonNode.textOrNull(): String? = if (isTextual || isNumber) asText() else null
    private fun JsonNode.asIntOrNull(): Int? =
        if (isNumber || (isTextual && asText().toIntOrNull() != null)) asInt() else null
}
