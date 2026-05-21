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
    @JsonProperty("timeline_pinned_user_ids") val timelinePinnedUserIds: List<String> = emptyList(),
    @JsonProperty("product_type") val productType: String? = null,
    @JsonProperty("carousel_media_count") val carouselMediaCount: Int? = null,
    @JsonProperty("image_versions2") val imageVersions2: RapidApiImageVersions2? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RapidApiCaption(
    @JsonProperty("text") val text: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RapidApiImageVersions2(
    @JsonProperty("candidates") val candidates: List<RapidApiImageCandidate> = emptyList()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RapidApiImageCandidate(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("width") val width: Int? = null,
    @JsonProperty("height") val height: Int? = null
)

data class RawInstagramPost(
    val pk: String,
    val code: String,
    val likeCount: Int,
    val commentCount: Int,
    val viewCount: Long?,
    val captionText: String?,
    val takenAt: Long,
    val isPinned: Boolean = false,
    val productType: String? = null,
    val carouselMediaCount: Int = 1,
    val imageUrl: String? = null
)

// ── User details ─────────────────────────────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class RapidApiUserDetailsResponse(
    @JsonProperty("user") val user: RapidApiUserDetails? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RapidApiUserDetails(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("follower_count") val followerCount: Int? = null,
    @JsonProperty("following_count") val followingCount: Int? = null,
    @JsonProperty("media_count") val mediaCount: Int? = null,
    @JsonProperty("biography") val biography: String? = null,
    @JsonProperty("external_url") val externalUrl: String? = null,
    @JsonProperty("public_email") val publicEmail: String? = null,
    @JsonProperty("public_phone_number") val publicPhoneNumber: String? = null,
    @JsonProperty("is_verified") val isVerified: Boolean? = null,
    @JsonProperty("is_business") val isBusiness: Boolean? = null,
    @JsonProperty("account_type") val accountType: Int? = null,
    @JsonProperty("category") val category: String? = null,
    @JsonProperty("has_highlight_reels") val hasHighlightReels: Boolean? = null,
    @JsonProperty("total_clips_count") val totalClipsCount: Int? = null,
    @JsonProperty("is_private") val isPrivate: Boolean? = null,
    @JsonProperty("latest_reel_media") val latestReelMedia: Long? = null,
    @JsonProperty("should_show_public_contacts") val shouldShowPublicContacts: Boolean? = null,
    @JsonProperty("bio_links") val bioLinks: List<RapidApiBioLink>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RapidApiBioLink(
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("link_type") val linkType: String? = null
)

/**
 * Bogaty wynik wywołania /user/details – wszystkie pola istotne dla analizy profilu.
 */
data class RawInstagramUserDetails(
    val instagramUserId: String,
    val followerCount: Int?,
    val followingCount: Int?,
    val mediaCount: Int?,
    val biography: String?,
    val externalUrl: String?,
    /** true gdy profil ma uzupełniony publiczny e-mail LUB numer telefonu */
    val hasContactData: Boolean,
    val publicEmail: String?,
    val publicPhoneNumber: String?,
    val isVerified: Boolean,
    val isBusiness: Boolean,
    /** Typ konta: 1 = personal, 2 = creator, 3 = business/professional */
    val accountType: Int?,
    val category: String?,
    val hasHighlightReels: Boolean,
    val totalClipsCount: Int,
    val isPrivate: Boolean,
    /** Unix timestamp ostatniego Reela – pozwala ocenić aktywność wideo */
    val latestReelMedia: Long?
)

// ── Stories ───────────────────────────────────────────────────────────────────

@JsonIgnoreProperties(ignoreUnknown = true)
data class RapidApiStoryItem(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("taken_at") val takenAt: Long? = null
)

/** Minimalne dane story – tylko timestamp potrzebny do liczenia aktywności. */
data class RawInstagramStory(
    val storyId: String,
    val takenAt: Long
)

/**
 * Klient HTTP do pobierania danych z RapidAPI (ig-scraper5).
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
        private const val FULL_PAGE_SIZE = 12
    }

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(timeoutSeconds))
        .build()

    fun fetchPosts(username: String): List<RawInstagramPost> {
        return fetchPage(username, afterCursor = null).first
    }

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

            if (page == 1) result.addAll(pinned)

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

    /**
     * Pobiera szczegóły profilu z /user/details. Zwraca bogaty obiekt ze wszystkimi
     * metrykami potrzebnymi do analizy trendu, popularności i SEO konta.
     *
     * @return dane profilu lub null gdy API nie zwróciło pola "id"
     * @throws RapidApiException gdy API zwraca błąd HTTP
     */
    fun fetchUserDetails(username: String): RawInstagramUserDetails? {
        if (!setOf("carspa.official", "carslab_pl", "carartdetailing").contains(username)) {
            return null
        }

        val url = "https://$apiHost/user/details?username=${URLEncoder.encode(username, StandardCharsets.UTF_8)}"

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
                message = "RapidAPI /user/details zwrócił status ${response.statusCode()} dla @$username"
            )
        }

        val parsed = objectMapper.readValue(response.body(), RapidApiUserDetailsResponse::class.java)
        val user = parsed.user ?: return null
        val userId = user.id ?: return null

        val hasContactData = !user.publicEmail.isNullOrBlank() || !user.publicPhoneNumber.isNullOrBlank()

        return RawInstagramUserDetails(
            instagramUserId = userId,
            followerCount = user.followerCount,
            followingCount = user.followingCount,
            mediaCount = user.mediaCount,
            biography = user.biography?.takeIf { it.isNotBlank() },
            externalUrl = user.externalUrl?.takeIf { it.isNotBlank() },
            hasContactData = hasContactData,
            publicEmail = user.publicEmail?.takeIf { it.isNotBlank() },
            publicPhoneNumber = user.publicPhoneNumber?.takeIf { it.isNotBlank() },
            isVerified = user.isVerified ?: false,
            isBusiness = user.isBusiness ?: false,
            accountType = user.accountType,
            category = user.category?.takeIf { it.isNotBlank() },
            hasHighlightReels = user.hasHighlightReels ?: false,
            totalClipsCount = user.totalClipsCount ?: 0,
            isPrivate = user.isPrivate ?: false,
            latestReelMedia = user.latestReelMedia
        ).also {
            log.debug(
                "Instagram user details: @{} → userId={}, followers={}, media={}",
                username, userId, it.followerCount, it.mediaCount
            )
        }
    }

    /**
     * Pobiera aktywne stories dla podanego Instagram user ID.
     * Zwraca wyłącznie identyfikatory i timestamp publikacji – dane zdjęć nie są zbierane.
     *
     * @throws RapidApiException gdy API zwraca błąd HTTP
     */
    fun fetchStories(instagramUserId: String): List<RawInstagramStory> {
        val url = "https://$apiHost/user/stories?user_id=${URLEncoder.encode(instagramUserId, StandardCharsets.UTF_8)}"

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
                username = instagramUserId,
                statusCode = response.statusCode(),
                message = "RapidAPI /user/stories zwrócił status ${response.statusCode()} dla userId=$instagramUserId"
            )
        }

        val items = objectMapper.readValue(
            response.body(),
            objectMapper.typeFactory.constructCollectionType(List::class.java, RapidApiStoryItem::class.java)
        ) as List<RapidApiStoryItem>

        return items.mapNotNull { item ->
            val id = item.id ?: return@mapNotNull null
            val takenAt = item.takenAt ?: return@mapNotNull null
            RawInstagramStory(storyId = id, takenAt = takenAt)
        }
    }

    // ── prywatne ──────────────────────────────────────────────────────────────

    private fun fetchPage(username: String, afterCursor: String?): Pair<List<RawInstagramPost>, String?> {
        if (!setOf("carspa.official", "carslab_pl", "carartdetailing").contains(username)) {
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
                carouselMediaCount = if (isCarousel) node.carouselMediaCount ?: 1 else 1,
                imageUrl = node.imageVersions2?.candidates?.firstOrNull()?.url
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
