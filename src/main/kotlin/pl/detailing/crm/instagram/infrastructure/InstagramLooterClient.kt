package pl.detailing.crm.instagram.infrastructure

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

/**
 * Klient RapidAPI "Instagram Looter" (instagram-looter2.p.rapidapi.com).
 *
 * Optymalizacja quoty:
 * - każdy request przechodzi przez [RapidApiCallGate] (rate limit + dzienny budżet + metryki),
 * - parametr `fields=` na każdym wywołaniu – pobieramy wyłącznie pola, które przetwarzamy
 *   (mniejsze payloady + minimalizacja danych zgodna z RODO),
 * - posty pobierane per strona (12 szt.) – o liczbie stron decyduje warstwa sync,
 * - szczegóły profilu jednym wywołaniem /profile2 (zwraca też `pk`, więc nie potrzebujemy
 *   osobnego wywołania /id do rozwiązania user ID).
 */
@Component
class InstagramLooterClient(
    private val objectMapper: ObjectMapper,
    private val callGate: RapidApiCallGate,
    @Value("\${instagram.rapidapi.key}") private val apiKey: String,
    @Value("\${instagram.looter.host:instagram-looter2.p.rapidapi.com}") private val apiHost: String,
    @Value("\${instagram.rapidapi.timeout-seconds:30}") private val timeoutSeconds: Long
) : InstagramDataProvider {

    private val log = LoggerFactory.getLogger(InstagramLooterClient::class.java)

    override val providerName: String = "LOOTER"

    companion object {
        const val PAGE_SIZE = 12

        private const val PROFILE_FIELDS =
            "pk,username,follower_count,following_count,media_count,biography,external_url,bio_links," +
                "is_private,is_verified,is_business,is_professional_account,category,account_type," +
                "total_clips_count,public_email,public_phone_number,latest_reel_media"

        private const val FEED_FIELDS =
            "more_available,next_max_id," +
                "items[].pk,items[].code,items[].taken_at,items[].like_count,items[].comment_count," +
                "items[].play_count,items[].view_count,items[].media_type,items[].product_type," +
                "items[].carousel_media_count,items[].caption.text,items[].like_and_view_counts_disabled," +
                "items[].timeline_pinned_user_ids"
    }

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(timeoutSeconds))
        .build()

    override fun fetchUserDetails(username: String): RawInstagramUserDetails? {
        val body = get(
            endpoint = "/profile2",
            username = username,
            url = "https://$apiHost/profile2?username=${encode(username)}&fields=${encode(PROFILE_FIELDS)}"
        )

        val node = unwrap(body)
        val userId = node.path("pk").textOrNull() ?: return null

        val publicEmail = node.path("public_email").textOrNull()?.takeIf { it.isNotBlank() }
        val publicPhone = node.path("public_phone_number").textOrNull()?.takeIf { it.isNotBlank() }
        val externalUrl = node.path("external_url").textOrNull()?.takeIf { it.isNotBlank() }
            ?: node.path("bio_links").firstOrNull()?.path("url")?.textOrNull()?.takeIf { it.isNotBlank() }

        return RawInstagramUserDetails(
            instagramUserId = userId,
            followerCount = node.path("follower_count").asIntOrNull(),
            followingCount = node.path("following_count").asIntOrNull(),
            mediaCount = node.path("media_count").asIntOrNull(),
            biography = node.path("biography").textOrNull()?.takeIf { it.isNotBlank() },
            externalUrl = externalUrl,
            hasContactData = publicEmail != null || publicPhone != null,
            publicEmail = publicEmail,
            publicPhoneNumber = publicPhone,
            isVerified = node.path("is_verified").asBoolean(false),
            isBusiness = node.path("is_business").asBoolean(false),
            accountType = node.path("account_type").asIntOrNull(),
            category = node.path("category").textOrNull()?.takeIf { it.isNotBlank() },
            hasHighlightReels = node.path("has_highlight_reels").asBoolean(false),
            totalClipsCount = node.path("total_clips_count").asIntOrNull() ?: 0,
            isPrivate = node.path("is_private").asBoolean(false),
            latestReelMedia = node.path("latest_reel_media").asLongOrNull()
        ).also {
            log.debug(
                "Looter: @{} → userId={}, followers={}, media={}",
                username, userId, it.followerCount, it.mediaCount
            )
        }
    }

    override fun fetchPostsPage(username: String, instagramUserId: String?, cursor: String?): InstagramPostsPage {
        val userId = instagramUserId ?: throw InstagramProviderException(
            username = username,
            statusCode = null,
            message = "Brak instagramUserId dla @$username – wymagany przez /user-feeds. " +
                "Sync szczegółów profilu musi wykonać się przed syncem postów."
        )

        val cursorParam = cursor?.let { "&max_id=${encode(it)}" } ?: ""
        val body = get(
            endpoint = "/user-feeds",
            username = username,
            url = "https://$apiHost/user-feeds?id=${encode(userId)}&count=$PAGE_SIZE$cursorParam" +
                "&fields=${encode(FEED_FIELDS)}"
        )

        val node = unwrap(body)
        val items = node.path("items")

        val posts = items.mapNotNull { item -> mapPost(item) }

        return InstagramPostsPage(
            posts = posts,
            nextCursor = node.path("next_max_id").textOrNull(),
            hasMore = node.path("more_available").asBoolean(false)
        )
    }

    // ── prywatne ──────────────────────────────────────────────────────────────

    private fun mapPost(item: JsonNode): RawInstagramPost? {
        val pk = item.path("pk").textOrNull() ?: return null
        val code = item.path("code").textOrNull() ?: return null
        val takenAt = item.path("taken_at").asLongOrNull() ?: return null

        val mediaType = item.path("media_type").asIntOrNull()
        val productType = item.path("product_type").textOrNull() ?: when (mediaType) {
            1 -> "feed_item"
            2 -> "video"
            8 -> "carousel_container"
            else -> null
        }
        val isCarousel = productType == "carousel_container"

        return RawInstagramPost(
            pk = pk,
            code = code,
            likeCount = item.path("like_count").asIntOrNull() ?: 0,
            commentCount = item.path("comment_count").asIntOrNull() ?: 0,
            viewCount = item.path("play_count").asLongOrNull() ?: item.path("view_count").asLongOrNull(),
            captionText = item.path("caption").path("text").textOrNull()?.takeIf { it.isNotBlank() },
            takenAt = takenAt,
            isPinned = item.path("timeline_pinned_user_ids").let { it.isArray && it.size() > 0 },
            productType = productType,
            carouselMediaCount = if (isCarousel) item.path("carousel_media_count").asIntOrNull() ?: 1 else 1,
            likeCountHidden = item.path("like_and_view_counts_disabled").asBoolean(false)
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
                    message = "Instagram Looter $endpoint zwrócił status ${response.statusCode()} dla @$username"
                )
            }
            response.body()
        }

    /**
     * Dokumentacja Loothera pokazuje odpowiedzi opakowane w {"status":200,"body":{...}} –
     * defensywnie obsługujemy oba warianty (z wrapperem i bez).
     */
    private fun unwrap(body: String): JsonNode {
        val root = objectMapper.readTree(body)
        return if (root.has("body") && root.path("body").isObject) root.path("body") else root
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    /** Tekst węzła; obsługuje też liczby (pk potrafi przyjść jako number). Null dla null/missing. */
    private fun JsonNode.textOrNull(): String? = if (isTextual || isNumber) asText() else null

    private fun JsonNode.asIntOrNull(): Int? = if (isNumber || (isTextual && asText().toIntOrNull() != null)) asInt() else null

    private fun JsonNode.asLongOrNull(): Long? = if (isNumber || (isTextual && asText().toLongOrNull() != null)) asLong() else null

    private fun JsonNode.firstOrNull(): JsonNode? = if (isArray && size() > 0) get(0) else null
}
