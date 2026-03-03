package pl.detailing.crm.instagram.infrastructure

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Surowe DTO z odpowiedzi RapidAPI ig-scraper5.
 * Pola, które nie są potrzebne, są ignorowane.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class RapidApiPostsResponse(
    @JsonProperty("items") val items: List<RapidApiItem> = emptyList()
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
    @JsonProperty("taken_at") val takenAt: Long? = null
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
    val takenAt: Long
)

/**
 * Klient HTTP do pobierania postów z RapidAPI (ig-scraper5).
 *
 * Konfiguracja przez application.properties:
 *   instagram.rapidapi.key     – klucz RapidAPI
 *   instagram.rapidapi.host    – host RapidAPI (domyślnie ig-scraper5.p.rapidapi.com)
 *   instagram.rapidapi.timeout-seconds – timeout żądania (domyślnie 30)
 */
@Component
class RapidApiInstagramClient(
    private val objectMapper: ObjectMapper,
    @Value("\${instagram.rapidapi.key}") private val apiKey: String,
    @Value("\${instagram.rapidapi.host:ig-scraper5.p.rapidapi.com}") private val apiHost: String,
    @Value("\${instagram.rapidapi.timeout-seconds:30}") private val timeoutSeconds: Long
) {
    private val log = LoggerFactory.getLogger(RapidApiInstagramClient::class.java)

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(timeoutSeconds))
        .build()

    /**
     * Pobiera posty dla danej nazwy użytkownika.
     *
     * @return lista surowych postów lub pusta lista gdy profil nie ma postów
     * @throws RapidApiException gdy API zwraca błąd (np. 404 – konto usunięte, 429 – rate limit)
     */
    fun fetchPosts(username: String): List<RawInstagramPost> {
        val url = "https://$apiHost/user/posts?username=$username"

        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .GET()
            .header("x-rapidapi-host", apiHost)
            .header("x-rapidapi-key", apiKey)
            .build()

        log.debug("Fetching Instagram posts for @{}", username)

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            throw RapidApiException(
                username = username,
                statusCode = response.statusCode(),
                message = "RapidAPI zwrócił status ${response.statusCode()} dla @$username"
            )
        }

        val parsed = objectMapper.readValue(response.body(), RapidApiPostsResponse::class.java)

        return parsed.items.mapNotNull { item ->
            val node = item.node ?: return@mapNotNull null
            val pk = node.pk ?: return@mapNotNull null
            val code = node.code ?: return@mapNotNull null
            val takenAt = node.takenAt ?: return@mapNotNull null

            RawInstagramPost(
                pk = pk,
                code = code,
                likeCount = node.likeCount ?: 0,
                commentCount = node.commentCount ?: 0,
                viewCount = node.viewCount,
                captionText = node.caption?.text,
                takenAt = takenAt
            )
        }
    }
}

class RapidApiException(
    val username: String,
    val statusCode: Int,
    message: String
) : RuntimeException(message)
