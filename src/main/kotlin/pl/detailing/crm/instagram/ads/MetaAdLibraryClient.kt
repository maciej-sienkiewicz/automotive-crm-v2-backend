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
    @Value("\${meta.ads.page-size:200}") private val pageSize: Int,
    // Odkrywanie po frazie ma własny, większy rozmiar strony: fraza jak „ceramika"
    // zwraca setki reklam, a większa strona to mniej wywołań na tę samą liczbę wyników
    // — taniej dla wspólnego limitu tokena niż dokładanie kolejnych stron.
    @Value("\${meta.ads.discovery.page-size:500}") private val discoveryPageSize: Int
) {
    private val log = LoggerFactory.getLogger(MetaAdLibraryClient::class.java)

    companion object {
        /** Twardy limit Meta: jedno zapytanie obsłuży najwyżej tyle stron. */
        const val MAX_PAGES_PER_CALL = 10

        /** Biblioteka trzyma reklamy rok wstecz — starszych nie ma sensu szukać. */
        const val RETENTION_DAYS = 365L

        /**
         * Pełne pola dla nocnego synca obserwowanych profili.
         *
         * `ad_creative_bodies` i `ad_creative_link_descriptions` to TREŚĆ reklamy —
         * jedyna część kreacji, jaką `ads_archive` w ogóle oddaje (grafiki nie ma
         * w żadnym polu). Dzięki nim okno reklamy pokazujemy u siebie, zamiast
         * wypychać użytkownika do Biblioteki po sam tekst.
         *
         * `ad_snapshot_url` świadomie NIE jest pobierane: Meta zwraca je z naszym
         * tokenem w adresie, a link do reklamy składamy sami z jej identyfikatora.
         */
        private const val FIELDS =
            "id,page_id,page_name,ad_creative_link_titles,ad_creative_bodies," +
                "ad_creative_link_descriptions,ad_delivery_start_time,ad_delivery_stop_time," +
                "eu_total_reach,age_country_gender_reach_breakdown,target_ages,target_gender," +
                "target_locations,beneficiary_payers,publisher_platforms"

        /**
         * LEKKI zestaw pól dla odkrywania po frazie — świadomie BEZ
         * `age_country_gender_reach_breakdown`.
         *
         * To pole (rozbicie wiek/płeć/kraj) jest ciężkie: przy szerokiej frazie jak
         * „detailing" (~kilka tysięcy reklam) i sensownym `limit` Meta odrzuca zapytanie
         * błędem code=1 / HTTP 500 „Please reduce the amount of data you're asking for".
         * Nocny sync profili może brać pełne pola, bo pyta o pojedyncze strony (mały
         * wynik); skan po treści zwraca tysiące reklam, więc payload musi być chudy.
         *
         * Zasięg bierzemy z lekkiego `eu_total_reach` (jedna liczba) zamiast liczyć go
         * z rozbicia PL — rozbicia i tak nie da się pobrać hurtowo dla szerokiej frazy.
         */
        private const val DISCOVERY_FIELDS =
            "id,page_id,page_name,ad_delivery_start_time,ad_delivery_stop_time," +
                "eu_total_reach,target_locations"

        private const val SEARCH_FIELDS = "page_id,page_name,ad_delivery_start_time"

        /**
         * Domena reklamodawcy — jedyne wskazanie na stronę firmy, jakie niesie reklama.
         * Trzymana osobno, bo jest DODATKIEM: gdyby Meta kiedyś przestała ją oddawać,
         * zapytanie ma polecieć bez niej, a nie wysypać kalendarz.
         */
        private const val OPTIONAL_FIELD = "ad_creative_link_captions"

        /** Kod Meta dla „nie znam takiego pola”. */
        private const val ERROR_UNKNOWN_FIELD = 100

        /**
         * Meta tak skarży się na zbyt duży payload. Dla odkrywania to realne ryzyko
         * (tysiące reklam na frazę), więc pole opcjonalne odpada także po tym błędzie.
         */
        private const val TOO_MUCH_DATA = "reduce the amount of data"

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

    /**
     * Czy Meta nadal zna [OPTIONAL_FIELD]. Zgaszone raz, zostaje zgaszone do restartu:
     * nie ma sensu dopytywać o pole, które właśnie zostało odrzucone.
     */
    @Volatile
    private var optionalFieldSupported: Boolean = true

    private fun fieldsWithOptional(base: String): String =
        if (optionalFieldSupported) "$base,$OPTIONAL_FIELD" else base

    /**
     * Wykonuje zapytanie, a gdy Meta odrzuci je z powodu nieznanego pola — powtarza
     * je raz bez pola opcjonalnego. Dzięki temu dołożenie domeny reklamodawcy nie
     * może zepsuć niczego, co działało wcześniej.
     */
    private fun <T> withOptionalField(block: () -> T): T =
        try {
            block()
        } catch (e: MetaAdsException) {
            val rejectsField = e.errorCode == ERROR_UNKNOWN_FIELD ||
                e.message?.contains(TOO_MUCH_DATA, ignoreCase = true) == true
            if (optionalFieldSupported && rejectsField) {
                optionalFieldSupported = false
                log.warn("Biblioteka reklam Meta nie zna pola {} — dalej bez niego ({})", OPTIONAL_FIELD, e.message)
                block()
            } else {
                throw e
            }
        }

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
            .flatMap { batch -> withOptionalField { fetchBatch(batch) } }
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
     * Aktywne reklamy pasujące treścią do frazy — z całej Polski, niezależnie od
     * strony. Serce odkrywania obszaru: nie znamy stron z góry, więc pytamy po
     * treści (`search_terms`) i dopiero u siebie filtrujemy po lokalizacji.
     *
     * Pola bierzemy LEKKIE ([DISCOVERY_FIELDS]) — szeroka fraza zwraca tysiące
     * reklam, a pełny zestaw wywraca zapytanie na rozmiarze odpowiedzi.
     * Paginację ucinamy po [maxPages] —
     * fraza tak ogólna, że nie mieści się w tylu stronach, i tak jest bezużyteczna
     * w tabeli, a każda strona to wywołanie z jednego, wspólnego limitu Meta.
     */
    fun fetchActiveAdsByTerm(term: String, maxPages: Int): DiscoveryAdsResult {
        if (!enabled) return DiscoveryAdsResult(emptyList(), false)
        val query = term.trim().takeIf { it.length >= MIN_SEARCH_LENGTH }
            ?: return DiscoveryAdsResult(emptyList(), false)

        return withOptionalField { discoveryPages(query, maxPages) }
    }

    private fun discoveryPages(query: String, maxPages: Int): DiscoveryAdsResult {
        val ads = mutableListOf<RawMetaAd>()
        var after: String? = null
        var page = 0
        var truncated = false
        val cap = maxPages.coerceIn(1, MAX_PAGES)

        do {
            val body = callGate.call("ads_archive_discovery") { get(buildDiscoveryUrl(query, after)) }
            val root = objectMapper.readTree(body)

            root.path("data").forEach { node -> MetaAdParser.parseAd(node)?.let { ads += it } }

            after = root.path("paging").path("cursors").path("after").textOrNull()
            page++
            val hasMore = after != null && root.path("data").size() > 0
            if (hasMore && page >= cap) {
                truncated = true
                break
            }
        } while (after != null && root.path("data").size() > 0 && page < cap)

        return DiscoveryAdsResult(ads, truncated)
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

        val body = withOptionalField { callGate.call("ads_archive_search") { get(buildSearchUrl(query)) } }
        val root = objectMapper.readTree(body)

        return root.path("data")
            .mapNotNull { node ->
                val pageId = node.path("page_id").textOrNull() ?: return@mapNotNull null
                SearchHit(
                    pageId = pageId,
                    pageName = node.path("page_name").textOrNull()?.trim().orEmpty(),
                    start = MetaAdParser.parseDate(node.path("ad_delivery_start_time").textOrNull()),
                    caption = node.path("ad_creative_link_captions").firstOrNull()?.textOrNull()
                )
            }
            .groupBy { it.pageId }
            .map { (pageId, hits) ->
                MetaPageCandidate(
                    pageId = pageId,
                    pageName = hits.firstNotNullOfOrNull { it.pageName.takeIf(String::isNotBlank) } ?: pageId,
                    ads = hits.size,
                    lastStart = hits.mapNotNull { it.start }.maxOrNull(),
                    domain = AdvertiserInstagram.primaryHost(hits.map { it.caption })
                )
            }
            // Treść reklamy była sitem po stronie Meta; o tym, co zobaczy człowiek,
            // decyduje nazwa strony — inaczej lista to firmy z przypadkowo zbieżnym
            // słowem w tekście reklamy.
            .let { MetaPageSearch.rank(query, it) }
            .take(MAX_SEARCH_RESULTS)
    }

    /**
     * Kandydat zbudowany z SAMEGO numeru strony — po to, żeby dało się potwierdzić,
     * czyj on jest, zanim ktokolwiek go zapisze.
     *
     * Pusta lista reklam nie znaczy „zły numer": biblioteka zna wyłącznie
     * reklamodawców, więc strona, która nic nie reklamowała, wygląda tak samo jak
     * numer wzięty z sufitu. Zwracamy wtedy kandydata bez nazwy i to wołający
     * decyduje, co z tym zrobić.
     */
    fun describePage(pageId: String): MetaPageCandidate? {
        if (!enabled) return null

        val ads = try {
            fetchAdsForPages(listOf(pageId)).filter { it.pageId == pageId }
        } catch (e: MetaAdsException) {
            log.warn("Meta Ad Library: sprawdzenie strony {} nie powiodło się — {}", pageId, e.message)
            return null
        }

        return MetaPageCandidate(
            pageId = pageId,
            pageName = ads.firstNotNullOfOrNull { it.pageName?.trim()?.takeIf(String::isNotBlank) }.orEmpty(),
            ads = ads.size,
            lastStart = ads.maxOfOrNull { it.deliveryStart },
            domain = AdvertiserInstagram.primaryHost(ads.map { it.linkCaption })
        )
    }

    /**
     * Alias (facebook.com/CarArtDetailing) → numer strony, najkrótszą drogą.
     *
     * Odczyt węzła strony po nazwie użytkownika wymaga uprawnienia Page Public
     * Content Access, przechodzącego App Review — bez niego Meta odpowiada błędem.
     * Próbujemy mimo to, bo gdy uprawnienie jest, odpowiedź jest DOKŁADNA: numer
     * tej i tylko tej strony. Gdy go nie ma, wołający ma drogę zapasową przez
     * wyszukiwanie po nazwie, więc ta próba nic nie kosztuje poza jednym wywołaniem.
     */
    fun resolveAlias(alias: String): MetaPageCandidate? {
        if (!enabled || alias.isBlank()) return null

        return try {
            val body = callGate.call("page_lookup") {
                get(
                    "https://graph.facebook.com/$apiVersion/${encode(alias)}" +
                        "?access_token=${encode(accessToken)}&fields=id,name"
                )
            }
            val node = objectMapper.readTree(body)
            val id = node.path("id").textOrNull()?.takeIf { it.all(Char::isDigit) } ?: return null
            MetaPageCandidate(
                pageId = id,
                pageName = node.path("name").textOrNull()?.trim().orEmpty(),
                ads = 0,
                lastStart = null
            )
        } catch (e: MetaAdsException) {
            log.debug("Meta: odczyt strony po aliasie „{}” niedostępny — {}", alias, e.message)
            null
        }
    }

    /** Jedna reklama z wyszukiwania — tylko to, z czego składamy kandydata. */
    private data class SearchHit(
        val pageId: String,
        val pageName: String,
        val start: LocalDate?,
        val caption: String?
    )

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
            append("&fields=").append(encode(fieldsWithOptional(SEARCH_FIELDS)))
            append("&limit=").append(SEARCH_PAGE_SIZE)
        }
    }

    /**
     * Odkrywanie: aktywne reklamy dla frazy w Polsce, lekkie pola.
     *
     * `ad_active_status=ACTIVE` — bo tabela mówi „ile AKTYWNYCH reklam". `search_terms`
     * przeszukuje treść reklamy; filtr po lokalizacji robimy u siebie, bo `ads_archive`
     * nie przyjmuje targetu miejscowości jako parametru zapytania.
     */
    private fun buildDiscoveryUrl(term: String, after: String?): String {
        val since = LocalDate.now().minusDays(RETENTION_DAYS)
        return buildString {
            append("https://graph.facebook.com/$apiVersion/ads_archive")
            append("?access_token=").append(encode(accessToken))
            append("&ad_reached_countries=").append(encode("[\"PL\"]"))
            append("&search_terms=").append(encode(term))
            append("&ad_type=ALL")
            append("&ad_active_status=ACTIVE")
            append("&ad_delivery_date_min=").append(since)
            append("&fields=").append(encode(fieldsWithOptional(DISCOVERY_FIELDS)))
            append("&limit=").append(discoveryPageSize.coerceIn(1, 500))
            if (after != null) append("&after=").append(encode(after))
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
            append("&fields=").append(encode(fieldsWithOptional(FIELDS)))
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
