package pl.detailing.crm.trends.expansion

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import pl.detailing.crm.trends.config.DataForSeoProperties
import pl.detailing.crm.trends.exception.DataForSeoApiException
import pl.detailing.crm.trends.exception.DataForSeoAuthException
import pl.detailing.crm.trends.exception.TrendsRateLimitException
import pl.detailing.crm.trends.expansion.model.KeywordsForKeywordsRequest
import pl.detailing.crm.trends.searchvolume.model.DataForSeoResponse
import pl.detailing.crm.trends.searchvolume.model.SearchVolumeResultItem
import org.springframework.http.HttpStatusCode
import org.springframework.web.client.RestClientException

/**
 * Fetches related keyword suggestions from DataForSEO.
 *
 * Endpoint: POST /v3/keywords_data/google_ads/keywords_for_keywords/live
 * Given seed keywords, returns queries that people actually search for in the same topic.
 * Response items are structurally identical to search_volume/live results.
 */
@Component
class KeywordExpansionClient(
    private val dataForSeoRestClient: RestClient,
    private val props: DataForSeoProperties
) {
    private val log = LoggerFactory.getLogger(KeywordExpansionClient::class.java)

    private val objectMapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    companion object {
        const val KEYWORDS_FOR_KEYWORDS_PATH = "/v3/keywords_data/google_ads/keywords_for_keywords/live"
        const val MAX_SEEDS_PER_REQUEST = 20
        private val RETRYABLE_HTTP_CODES = setOf(429, 500, 502, 503, 504)
    }

    fun fetchRelatedKeywords(
        seeds: List<String>,
        locationCode: Int = 2616
    ): DataForSeoResponse<SearchVolumeResultItem> {
        require(seeds.size <= MAX_SEEDS_PER_REQUEST) {
            "Max $MAX_SEEDS_PER_REQUEST seeds per request, got ${seeds.size}"
        }

        val body = listOf(
            KeywordsForKeywordsRequest(
                keywords = seeds,
                locationCode = locationCode,
                includeSeedKeyword = true
            )
        )

        return executeWithRetry(body)
    }

    private fun executeWithRetry(body: Any): DataForSeoResponse<SearchVolumeResultItem> {
        var lastException: Exception? = null

        repeat(props.maxRetries) { attempt ->
            try {
                val start = System.currentTimeMillis()
                val json = doPost(body)
                log.info("keywords_for_keywords responded in {}ms (attempt {}/{})",
                    System.currentTimeMillis() - start, attempt + 1, props.maxRetries)
                return objectMapper.readValue(
                    json,
                    object : TypeReference<DataForSeoResponse<SearchVolumeResultItem>>() {}
                )
            } catch (ex: DataForSeoAuthException) {
                throw ex
            } catch (ex: TrendsRateLimitException) {
                log.warn("Rate limit hit, attempt {}/{}", attempt + 1, props.maxRetries)
                lastException = ex
                if (attempt + 1 < props.maxRetries) backoff(attempt)
            } catch (ex: DataForSeoApiException) {
                if (ex.statusCode in RETRYABLE_HTTP_CODES) {
                    log.warn("Transient HTTP {}, attempt {}/{}", ex.statusCode, attempt + 1, props.maxRetries)
                    lastException = ex
                    if (attempt + 1 < props.maxRetries) backoff(attempt)
                } else throw ex
            } catch (ex: RestClientException) {
                log.warn("Network error, attempt {}/{}: {}", attempt + 1, props.maxRetries, ex.message)
                lastException = ex
                if (attempt + 1 < props.maxRetries) backoff(attempt)
            }
        }

        throw DataForSeoApiException(
            "All ${props.maxRetries} attempts failed for keywords_for_keywords",
            cause = lastException
        )
    }

    private fun doPost(body: Any): String {
        val json = objectMapper.writeValueAsString(body)
        log.debug("POST {} payload: {}", KEYWORDS_FOR_KEYWORDS_PATH, json)

        return dataForSeoRestClient.post()
            .uri(KEYWORDS_FOR_KEYWORDS_PATH)
            .body(json)
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

    private fun backoff(attempt: Int) {
        val delay = props.backoffMillis * (1L shl attempt)
        log.info("Backing off {}ms", delay)
        Thread.sleep(delay)
    }
}
