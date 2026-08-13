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

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class Scraper5PostsResponse(
    @JsonProperty("items") val items: List<Scraper5Item> = emptyList(),
    @JsonProperty("after_cursor") val afterCursor: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class Scraper5Item(
    @JsonProperty("node") val node: Scraper5Node? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class Scraper5Node(
    @JsonProperty("pk") val pk: String? = null,
    @JsonProperty("code") val code: String? = null,
    @JsonProperty("like_count") val likeCount: Int? = null,
    @JsonProperty("comment_count") val commentCount: Int? = null,
    @JsonProperty("view_count") val viewCount: Long? = null,
    @JsonProperty("caption") val caption: Scraper5Caption? = null,
    @JsonProperty("taken_at") val takenAt: Long? = null,
    @JsonProperty("timeline_pinned_user_ids") val timelinePinnedUserIds: List<String> = emptyList(),
    @JsonProperty("product_type") val productType: String? = null,
    @JsonProperty("carousel_media_count") val carouselMediaCount: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class Scraper5Caption(
    @JsonProperty("text") val text: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class Scraper5UserDetailsResponse(
    @JsonProperty("user") val user: Scraper5UserDetails? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class Scraper5UserDetails(
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
    @JsonProperty("latest_reel_media") val latestReelMedia: Long? = null
)

/**
 * Legacy klient RapidAPI "ig-scraper5" – zachowany jako alternatywny dostawca
 * (instagram.provider=IG_SCRAPER5). Wszystkie wywołania przechodzą przez [RapidApiCallGate].
 */
@Component
class IgScraper5Client(
    private val objectMapper: ObjectMapper,
    private val callGate: RapidApiCallGate,
    @Value("\${instagram.rapidapi.key}") private val apiKey: String,
    @Value("\${instagram.rapidapi.host:ig-scraper5.p.rapidapi.com}") private val apiHost: String,
    @Value("\${instagram.rapidapi.timeout-seconds:30}") private val timeoutSeconds: Long
) : InstagramDataProvider {

    private val log = LoggerFactory.getLogger(IgScraper5Client::class.java)

    override val providerName: String = "IG_SCRAPER5"

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(timeoutSeconds))
        .build()

    override fun fetchUserDetails(username: String): RawInstagramUserDetails? {
        val body = get(
            endpoint = "/user/details",
            username = username,
            url = "https://$apiHost/user/details?username=${encode(username)}"
        )

        val parsed = objectMapper.readValue(body, Scraper5UserDetailsResponse::class.java)
        val user = parsed.user ?: return null
        val userId = user.id ?: return null

        return RawInstagramUserDetails(
            instagramUserId = userId,
            followerCount = user.followerCount,
            followingCount = user.followingCount,
            mediaCount = user.mediaCount,
            biography = user.biography?.takeIf { it.isNotBlank() },
            externalUrl = user.externalUrl?.takeIf { it.isNotBlank() },
            hasContactData = !user.publicEmail.isNullOrBlank() || !user.publicPhoneNumber.isNullOrBlank(),
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
        )
    }

    override fun fetchPostsPage(username: String, instagramUserId: String?, cursor: String?): InstagramPostsPage {
        val base = "https://$apiHost/user/posts?username=${encode(username)}"
        val url = if (cursor != null) "$base&after_cursor=${encode(cursor)}" else base

        val body = get(endpoint = "/user/posts", username = username, url = url)
        val parsed = objectMapper.readValue(body, Scraper5PostsResponse::class.java)

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
                captionText = node.caption?.text?.takeIf { it.isNotBlank() },
                takenAt = takenAt,
                isPinned = node.timelinePinnedUserIds.isNotEmpty(),
                productType = node.productType,
                carouselMediaCount = if (isCarousel) node.carouselMediaCount ?: 1 else 1
            )
        }

        return InstagramPostsPage(
            posts = posts,
            nextCursor = parsed.afterCursor,
            hasMore = parsed.afterCursor != null && posts.isNotEmpty()
        )
    }

    private fun get(endpoint: String, username: String, url: String): String =
        callGate.call(endpoint, username) {
            val request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
                .header("x-rapidapi-host", apiHost)
                .header("x-rapidapi-key", apiKey)
                .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

            if (response.statusCode() != 200) {
                throw InstagramProviderException(
                    username = username,
                    statusCode = response.statusCode(),
                    message = "ig-scraper5 $endpoint zwrócił status ${response.statusCode()} dla @$username"
                )
            }
            response.body()
        }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
