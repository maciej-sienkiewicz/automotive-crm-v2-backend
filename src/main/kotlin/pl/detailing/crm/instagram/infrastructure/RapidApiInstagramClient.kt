package pl.detailing.crm.instagram.infrastructure

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
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
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Surowe DTO z odpowiedzi RapidAPI ig-scraper5.
 * Pola, które nie są potrzebne, są ignorowane.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RapidApiPostsResponse(
    @JsonProperty("items") val items: List<RapidApiItem> = emptyList(),
    @JsonProperty("after_cursor") val afterCursor: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RapidApiItem(
    @JsonProperty("node") val node: RapidApiNode? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RapidApiNode(
    @JsonProperty("pk") val pk: String? = null,
    @JsonProperty("code") val code: String? = null,
    @JsonProperty("like_count") val likeCount: Int? = null,
    @JsonProperty("comment_count") val commentCount: Int? = null,
    @JsonProperty("view_count") val viewCount: Long? = null,
    @JsonProperty("caption") val caption: RapidApiCaption? = null,
    @JsonProperty("taken_at") val takenAt: Long? = null,
    /** Niepusta lista = post przypięty (może mieć starą datę, mimo że pojawia się na górze) */
    @JsonProperty("timeline_pinned_user_ids") val timelinePinnedUserIds: List<String> = emptyList(),
    /** Typ formatu: "feed_item" (zdjęcie), "clips" (Reels), "carousel_container" (karuzela) */
    @JsonProperty("product_type") val productType: String? = null,
    /** Liczba slajdów – obecne tylko dla carousel_container */
    @JsonProperty("carousel_media_count") val carouselMediaCount: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RapidApiCaption(
    @JsonProperty("text") val text: String? = null
)

data class RawInstagramPost(
    val pk: String,
    val code: String,
    val likeCount: Int,
    val commentCount: Int,
    val viewCount: Long?,
    val captionText: String?,
    val takenAt: Long,
    /** true = post przypięty przez autora (timeline_pinned_user_ids niepusta) */
    val isPinned: Boolean = false,
    /** "feed_item" | "clips" | "carousel_container" – null jeśli API nie zwróciło */
    val productType: String? = null,
    /** Liczba slajdów karuzeli; dla innych typów zawsze 1 */
    val carouselMediaCount: Int = 1
)

/**
 * Klient HTTP do pobierania postów z RapidAPI (ig-scraper5).
 *
 * Konfiguracja przez application.properties:
 *   instagram.rapidapi.key               – klucz RapidAPI
 *   instagram.rapidapi.host              – host RapidAPI (domyślnie ig-scraper5.p.rapidapi.com)
 *   instagram.rapidapi.timeout-seconds   – timeout żądania (domyślnie 30)
 */
@Component
class RapidApiInstagramClient(
    private val objectMapper: ObjectMapper,
    @Value("\${instagram.rapidapi.key}") private val apiKey: String,
    @Value("\${instagram.rapidapi.host:ig-scraper5.p.rapidapi.com}") private val apiHost: String,
    @Value("\${instagram.rapidapi.timeout-seconds:30}") private val timeoutSeconds: Long
) {
    private val log = LoggerFactory.getLogger(RapidApiInstagramClient::class.java)

    companion object {
        /** API zawsze zwraca 12 postów; mniej oznacza ostatnią stronę */
        private const val FULL_PAGE_SIZE = 12
    }

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(timeoutSeconds))
        .build()

    /**
     * Pobiera pojedynczą stronę postów (max 12) – używana przy regularnym (tygodniowym) sync.
     *
     * @return lista surowych postów lub pusta lista gdy profil nie ma postów
     * @throws RapidApiException gdy API zwraca błąd (np. 404 – konto usunięte, 429 – rate limit)
     */
    fun fetchPosts(username: String): List<RawInstagramPost> {
        return fetchPage(username, afterCursor = null).first
    }

    /**
     * Pobiera pełną historię postów do 12 miesięcy wstecz – używana przy pierwszym sync profilu.
     *
     * Algorytm:
     * - Strony pobierane od najnowszych do starszych (kolejność gwarantowana przez API).
     * - Posty przypięte (isPinned=true) z pierwszego batcha włączane zawsze, niezależnie od daty.
     * - Iteracja kończy się gdy:
     *   a) batch zawiera mniej niż 12 postów (brak kolejnej strony), LUB
     *   b) najstarszy nieprzypięty post w batchu jest sprzed okna 12 miesięcy.
     *
     * @throws RapidApiException gdy API zwraca błąd
     */
    fun fetchPostsFullHistory(username: String): List<RawInstagramPost> {
        val cutoff = Instant.now().minus(365, ChronoUnit.DAYS)
        val result = mutableListOf<RawInstagramPost>()
        var cursor: String? = null
        var page = 1

        while (true) {
            log.debug("Instagram full-history: @{} strona {} (cursor={})", username, page, cursor)

            val (posts, nextCursor) = fetchPage(username, cursor)

            if (posts.isEmpty()) break

            val pinned = posts.filter { it.isPinned }
            val regular = posts.filter { !it.isPinned }

            // Posty przypięte pojawiają się tylko w pierwszym batchu – zawsze je włączamy
            if (page == 1) result.addAll(pinned)

            // Nieprzypięte posty w oknie 12 miesięcy
            result.addAll(regular.filter { Instant.ofEpochSecond(it.takenAt).isAfter(cutoff) })

            val hasOlderPost = regular.any { !Instant.ofEpochSecond(it.takenAt).isAfter(cutoff) }
            val isLastPage = posts.size < FULL_PAGE_SIZE

            if (isLastPage || hasOlderPost) break

            cursor = nextCursor ?: break
            page++
        }

        log.info(
            "Instagram full-history: @{} pobrano {} postów z {} stron (okno 12 mies.)",
            username, result.size, page
        )

        return result
    }

    // ── prywatne ──────────────────────────────────────────────────────────────

    private fun fetchPage(username: String, afterCursor: String?): Pair<List<RawInstagramPost>, String?> {
        if(!setOf("carspa.official", "carslab_pl", "carartdetailing").contains(username)){
            return emptyList<RawInstagramPost>() to null
        }

        val url = buildUrl(username, afterCursor)

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .GET()
            .header("x-rapidapi-host", apiHost)
            .header("x-rapidapi-key", apiKey)
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            throw RapidApiException(
                username = username,
                statusCode = response.statusCode(),
                message = "RapidAPI zwrócił status ${response.statusCode()} dla @$username"
            )
        }

        val parsed = objectMapper.readValue(response.body(), RapidApiPostsResponse::class.java)

        val posts = parsed.items.mapNotNull { item ->
            val node = item.node ?: return@mapNotNull null
            val pk = node.pk ?: return@mapNotNull null
            val code = node.code ?: return@mapNotNull null
            val takenAt = node.takenAt ?: return@mapNotNull null

            val isCarousel = node.productType == "carousel_container"
            RawInstagramPost(
                pk = pk,
                code = code,
                likeCount = node.likeCount ?: 0,
                commentCount = node.commentCount ?: 0,
                viewCount = node.viewCount,
                captionText = node.caption?.text,
                takenAt = takenAt,
                isPinned = node.timelinePinnedUserIds.isNotEmpty(),
                productType = node.productType,
                carouselMediaCount = if (isCarousel) node.carouselMediaCount ?: 1 else 1
            )
        }

        return posts to parsed.afterCursor
    }

    private fun buildUrl(username: String, afterCursor: String?): String {
        val base = "https://$apiHost/user/posts?username=${URLEncoder.encode(username, StandardCharsets.UTF_8)}"
        return if (afterCursor != null) {
            "$base&after_cursor=${URLEncoder.encode(afterCursor, StandardCharsets.UTF_8)}"
        } else {
            base
        }
    }
}

class RapidApiException(
    val username: String,
    val statusCode: Int,
    message: String
) : RuntimeException(message)
