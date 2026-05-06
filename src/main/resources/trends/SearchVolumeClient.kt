package com.example.demo.trends.searchvolume.client

import com.example.demo.trends.config.DataForSeoProperties
import com.example.demo.trends.exception.DataForSeoApiException
import com.example.demo.trends.exception.DataForSeoAuthException
import com.example.demo.trends.exception.TrendsRateLimitException
import com.example.demo.trends.searchvolume.model.*
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

/**
 * HTTP client for DataForSEO keyword analytics APIs.
 *
 * PRIMARY endpoint (Search Volume):
 *   - POST /v3/keywords_data/google_ads/search_volume/live
 *     Docs: https://docs.dataforseo.com/v3/keywords_data/google_ads/search_volume/live
 *     → absolute volumes, CPC, competition, monthly_searches (12 months)
 *     → max 1000 keywords/request, rate limit 12 req/min
 *
 * SUPPLEMENTARY endpoint (Trends Explore):
 *   - POST /v3/keywords_data/dataforseo_trends/explore/live
 *     Docs: https://docs.dataforseo.com/v3/keywords_data/dataforseo_trends/explore/live
 *     → relative index 0-100, daily granularity, near-real-time
 *     → max 5 keywords/request
 *     → fills the gap from last Search Volume month to today
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
        // ── Search Volume (primary) ─────────────────────────────
        const val SEARCH_VOLUME_PATH = "/v3/keywords_data/google_ads/search_volume/live"
        const val LOCATIONS_PATH = "/v3/keywords_data/google_ads/locations"
        const val MAX_KEYWORDS_PER_REQUEST = 1000
        const val RATE_LIMIT_DELAY_MS = 5200L

        // ── Trends Explore (supplement) ─────────────────────────
        const val TRENDS_EXPLORE_PATH = "/v3/keywords_data/dataforseo_trends/explore/live"
        const val MAX_TRENDS_KEYWORDS_PER_REQUEST = 5

        private val RETRYABLE_HTTP_CODES = setOf(429, 500, 502, 503, 504)
    }

    // ── Search Volume ───────────────────────────────────────────────

    /**
     * Fetches keyword search volumes for a specific location.
     *
     * @param locationCode  Google Ads geotargeting location code (e.g. 2616 for Poland)
     * @param keywords      list of keywords (max 1000)
     * @param languageCode  language code (default: "pl")
     * @return Pair of (parsed response, raw JSON)
     */
    fun fetchSearchVolume(
        locationCode: Int,
        keywords: List<String>,
        languageCode: String = "pl"
    ): Pair<DataForSeoResponse<SearchVolumeResultItem>, String> {
        require(keywords.size <= MAX_KEYWORDS_PER_REQUEST) {
            "Max $MAX_KEYWORDS_PER_REQUEST keywords per request, got ${keywords.size}"
        }

        val request = listOf(
            SearchVolumeRequest(
                locationCode = locationCode,
                languageCode = languageCode,
                keywords = keywords
            )
        )

        return executeWithRetry(
            endpointName = "search_volume",
            path = SEARCH_VOLUME_PATH,
            requestBody = request,
            typeRef = object : TypeReference<DataForSeoResponse<SearchVolumeResultItem>>() {}
        )
    }

    // ── Locations (for reference / validation) ──────────────────────

    /**
     * Fetches all Google Ads geotargeting locations.
     * This is a GET endpoint — no request body.
     *
     * Used to validate/discover location_code values for Polish voivodeships.
     *
     * @return raw JSON string (large payload — ~40k locations)
     */
    fun fetchLocations(): String {
        log.info("Fetching Google Ads locations list")
        val startTime = System.currentTimeMillis()

        val response = dataForSeoRestClient.get()
            .uri(LOCATIONS_PATH)
            .exchange { _, clientResponse ->
                val statusCode = clientResponse.statusCode
                val body = clientResponse.body.bufferedReader().use { it.readText() }
                if (statusCode.is2xxSuccessful) body
                else throw DataForSeoApiException(
                    "Locations fetch failed (HTTP ${statusCode.value()}): ${body.take(500)}",
                    statusCode = statusCode.value()
                )
            }

        val elapsed = System.currentTimeMillis() - startTime
        log.info("Locations list fetched in {}ms ({} chars)", elapsed, response.length)
        return response
    }

    // ── Trends Explore (supplement) ─────────────────────────────

    /**
     * Fetches Trends Explore data for the given keywords and date range.
     * Used to fill the gap from the last Search Volume month to today.
     *
     * @param keywords max 5 keywords
     * @param dateFrom start date (YYYY-MM-DD)
     * @param dateTo end date (YYYY-MM-DD)
     * @return Pair of (parsed response, raw JSON)
     */
    fun fetchTrendsExplore(
        keywords: List<String>,
        dateFrom: String,
        dateTo: String
    ): Pair<DataForSeoResponse<ExploreResult>, String> {
        require(keywords.size <= MAX_TRENDS_KEYWORDS_PER_REQUEST) {
            "Max $MAX_TRENDS_KEYWORDS_PER_REQUEST keywords per Trends request, got ${keywords.size}"
        }

        val request = listOf(
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
            requestBody = request,
            typeRef = object : TypeReference<DataForSeoResponse<ExploreResult>>() {}
        )
    }

    // ── Retry logic ─────────────────────────────────────────────────

    private fun <T> executeWithRetry(
        endpointName: String,
        path: String,
        requestBody: Any,
        typeRef: TypeReference<T>
    ): Pair<T, String> {
        var lastException: Exception? = null

        for (attempt in 1..props.maxRetries) {
            try {
                val startTime = System.currentTimeMillis()
                val rawJson = doPost(path, requestBody)
                val elapsed = System.currentTimeMillis() - startTime

                log.info(
                    "DataForSEO [{}] response in {}ms (attempt {}/{})",
                    endpointName, elapsed, attempt, props.maxRetries
                )

                val parsed = objectMapper.readValue(rawJson, typeRef)
                return Pair(parsed, rawJson)

            } catch (ex: DataForSeoAuthException) {
                throw ex
            } catch (ex: TrendsRateLimitException) {
                log.warn("[{}] rate limit, attempt {}/{}", endpointName, attempt, props.maxRetries)
                lastException = ex
                if (attempt < props.maxRetries) backoff(attempt)
            } catch (ex: DataForSeoApiException) {
                if (ex.statusCode != null && ex.statusCode in RETRYABLE_HTTP_CODES) {
                    log.warn("[{}] transient error HTTP {}, attempt {}/{}",
                        endpointName, ex.statusCode, attempt, props.maxRetries)
                    lastException = ex
                    if (attempt < props.maxRetries) backoff(attempt)
                } else throw ex
            } catch (ex: RestClientException) {
                log.warn("[{}] network error, attempt {}/{}: {}",
                    endpointName, attempt, props.maxRetries, ex.message)
                lastException = ex
                if (attempt < props.maxRetries) backoff(attempt)
            }
        }

        throw DataForSeoApiException(
            "All ${props.maxRetries} attempts failed for [$endpointName]",
            cause = lastException
        )
    }

    private fun doPost(path: String, body: Any): String {
        val bodyJson = objectMapper.writeValueAsString(body)
        log.debug("POST {} body: {}", path, bodyJson)

        return dataForSeoRestClient.post()
            .uri(path)
            .body(bodyJson)
            .exchange { _, clientResponse ->
                val statusCode = clientResponse.statusCode
                val responseBody = clientResponse.body.bufferedReader().use { it.readText() }
                when {
                    statusCode.is2xxSuccessful -> responseBody
                    statusCode.isSameCodeAs(HttpStatusCode.valueOf(401)) ||
                    statusCode.isSameCodeAs(HttpStatusCode.valueOf(403)) ->
                        throw DataForSeoAuthException()
                    statusCode.isSameCodeAs(HttpStatusCode.valueOf(429)) ->
                        throw TrendsRateLimitException(statusCode = 429)
                    statusCode.is5xxServerError ->
                        throw DataForSeoApiException(
                            "Server error HTTP ${statusCode.value()}: ${responseBody.take(500)}",
                            statusCode = statusCode.value()
                        )
                    else ->
                        throw DataForSeoApiException(
                            "HTTP ${statusCode.value()}: ${responseBody.take(500)}",
                            statusCode = statusCode.value()
                        )
                }
            }
    }

    private fun backoff(attempt: Int) {
        val delay = props.backoffMillis * (1L shl (attempt - 1))
        log.info("Backing off {}ms before retry", delay)
        Thread.sleep(delay)
    }
}




