package pl.detailing.crm.trends.searchvolume.client

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import pl.detailing.crm.trends.config.DataForSeoProperties
import pl.detailing.crm.trends.exception.DataForSeoApiException
import pl.detailing.crm.trends.exception.DataForSeoAuthException
import pl.detailing.crm.trends.exception.TrendsRateLimitException
import pl.detailing.crm.trends.searchvolume.model.*

/**
 * HTTP client for the two DataForSEO endpoints used by this module:
 *
 *  1. Search Volume  — POST /v3/keywords_data/google_ads/search_volume/live
 *     Absolute volumes, CPC, competition, 12-month monthly_searches.
 *     Max 1000 keywords/request · rate limit 12 req/min.
 *
 *  2. Trends Explore — POST /v3/keywords_data/dataforseo_trends/explore/live
 *     Relative index 0–100, daily granularity, near-real-time.
 *     Max 5 keywords/request.
 *     Fills the gap from the last Search Volume month to today.
 */
@Component
class SearchVolumeClient(
    private val dataForSeoRestClient: RestClient,
    private val props: DataForSeoProperties
) {
    private val log = LoggerFactory.getLogger(SearchVolumeClient::class.java)

    private val objectMapper: ObjectMapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    companion object {
        const val SEARCH_VOLUME_PATH = "/v3/keywords_data/google_ads/search_volume/live"
        const val TRENDS_EXPLORE_PATH = "/v3/keywords_data/dataforseo_trends/explore/live"

        /** Safe inter-request delay to stay under the 12 req/min limit. */
        const val RATE_LIMIT_DELAY_MS = 5200L
        const val MAX_KEYWORDS_PER_SEARCH_VOLUME_REQUEST = 1000
        const val MAX_KEYWORDS_PER_TRENDS_REQUEST = 5

        private val RETRYABLE_HTTP_CODES = setOf(429, 500, 502, 503, 504)
    }

    fun fetchSearchVolume(
        locationCode: Int,
        keywords: List<String>,
        languageCode: String = "pl"
    ): DataForSeoResponse<SearchVolumeResultItem> {
        require(keywords.size <= MAX_KEYWORDS_PER_SEARCH_VOLUME_REQUEST) {
            "Search Volume accepts at most $MAX_KEYWORDS_PER_SEARCH_VOLUME_REQUEST keywords, got ${keywords.size}"
        }

        val body = listOf(
            SearchVolumeRequest(locationCode = locationCode, languageCode = languageCode, keywords = keywords)
        )
        return executeWithRetry(
            endpointName = "search_volume",
            path = SEARCH_VOLUME_PATH,
            requestBody = body,
            typeRef = object : TypeReference<DataForSeoResponse<SearchVolumeResultItem>>() {}
        )
    }

    fun fetchTrendsExplore(
        keywords: List<String>,
        dateFrom: String,
        dateTo: String
    ): DataForSeoResponse<ExploreResult> {
        require(keywords.size <= MAX_KEYWORDS_PER_TRENDS_REQUEST) {
            "Trends Explore accepts at most $MAX_KEYWORDS_PER_TRENDS_REQUEST keywords, got ${keywords.size}"
        }

        val body = listOf(
            TrendsExploreRequest(
                locationName = props.locationName,
                type = props.type,
                keywords = keywords,
                dateFrom = dateFrom,
                dateTo = dateTo
            )
        )
        return executeWithRetry(
            endpointName = "trends_explore",
            path = TRENDS_EXPLORE_PATH,
            requestBody = body,
            typeRef = object : TypeReference<DataForSeoResponse<ExploreResult>>() {}
        )
    }

    // ─── Internal: retry + backoff ────────────────────────────────────────────

    private fun <T> executeWithRetry(
        endpointName: String,
        path: String,
        requestBody: Any,
        typeRef: TypeReference<T>
    ): T {
        var lastException: Exception? = null

        repeat(props.maxRetries) { attempt ->
            try {
                val start = System.currentTimeMillis()
                val rawJson = doPost(path, requestBody)
                log.info("DataForSEO [{}] responded in {}ms (attempt {}/{})",
                    endpointName, System.currentTimeMillis() - start, attempt + 1, props.maxRetries)
                return objectMapper.readValue(rawJson, typeRef)
            } catch (ex: DataForSeoAuthException) {
                throw ex  // non-retryable
            } catch (ex: TrendsRateLimitException) {
                log.warn("[{}] rate limit hit, attempt {}/{}", endpointName, attempt + 1, props.maxRetries)
                lastException = ex
                if (attempt + 1 < props.maxRetries) backoff(attempt)
            } catch (ex: DataForSeoApiException) {
                if (ex.statusCode in RETRYABLE_HTTP_CODES) {
                    log.warn("[{}] transient HTTP {}, attempt {}/{}", endpointName, ex.statusCode, attempt + 1, props.maxRetries)
                    lastException = ex
                    if (attempt + 1 < props.maxRetries) backoff(attempt)
                } else throw ex
            } catch (ex: RestClientException) {
                log.warn("[{}] network error, attempt {}/{}: {}", endpointName, attempt + 1, props.maxRetries, ex.message)
                lastException = ex
                if (attempt + 1 < props.maxRetries) backoff(attempt)
            }
        }

        throw DataForSeoApiException(
            "All ${props.maxRetries} attempts failed for [$endpointName]",
            cause = lastException
        )
    }

    private fun doPost(path: String, body: Any): String {
        val bodyJson = objectMapper.writeValueAsString(body)
        log.debug("POST {} payload: {}", path, bodyJson)

        return dataForSeoRestClient.post()
            .uri(path)
            .body(bodyJson)
            .exchange { _, response ->
                val status = response.statusCode
                val responseBody = response.body.bufferedReader().use { it.readText() }
                when {
                    status.is2xxSuccessful -> responseBody
                    status.isSameCodeAs(HttpStatusCode.valueOf(401)) ||
                    status.isSameCodeAs(HttpStatusCode.valueOf(403)) -> throw DataForSeoAuthException()
                    status.isSameCodeAs(HttpStatusCode.valueOf(429)) -> throw TrendsRateLimitException(statusCode = 429)
                    status.is5xxServerError -> throw DataForSeoApiException(
                        "Server error HTTP ${status.value()}: ${responseBody.take(500)}",
                        statusCode = status.value()
                    )
                    else -> throw DataForSeoApiException(
                        "HTTP ${status.value()}: ${responseBody.take(500)}",
                        statusCode = status.value()
                    )
                }
            }
    }

    /** Exponential backoff: 1 s → 2 s → 4 s … */
    private fun backoff(attempt: Int) {
        val delay = props.backoffMillis * (1L shl attempt)
        log.info("Backing off {}ms before next retry", delay)
        Thread.sleep(delay)
    }
}
