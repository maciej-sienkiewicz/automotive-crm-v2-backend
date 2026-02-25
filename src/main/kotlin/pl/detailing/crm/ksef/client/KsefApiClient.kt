package pl.detailing.crm.ksef.client

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.ParameterizedTypeReference
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import pl.detailing.crm.ksef.config.KsefProperties
import pl.detailing.crm.shared.ValidationException

@Component
class KsefApiClient(
    @Qualifier("ksefRestClient") private val restClient: RestClient,
    private val properties: KsefProperties
) {
    private val log = LoggerFactory.getLogger(KsefApiClient::class.java)

    private val api get() = properties.apiPath

    // ─────────────────────────────────────────────────────────────────────
    // Auth
    // ─────────────────────────────────────────────────────────────────────

    fun getChallenge(): KsefChallengeResponse {
        return restClient.get()
            .uri("$api/auth/challenge")
            .retrieve()
            .body(KsefChallengeResponse::class.java)
            ?: throw KsefApiException("Empty response from auth/challenge")
    }

    fun getPublicKeyCertificates(): List<KsefPublicKeyCertificate> {
        return restClient.get()
            .uri("$api/security/public-key-certificates")
            .retrieve()
            .body(object : ParameterizedTypeReference<List<KsefPublicKeyCertificate>>() {})
            ?: emptyList()
    }

    fun submitKsefTokenAuth(request: KsefAuthKsefTokenRequest): KsefSignatureResponse {
        return try {
            restClient.post()
                .uri("$api/auth/ksef-token")
                .header("Content-Type", "application/json")
                .body(request)
                .retrieve()
                .body(KsefSignatureResponse::class.java)
                ?: throw KsefApiException("Empty response from auth/ksef-token")
        } catch (ex: HttpClientErrorException) {
            log.error("KSeF auth token submission failed: {} - {}", ex.statusCode, ex.responseBodyAsString)
            throw KsefApiException("KSeF authentication failed: ${ex.statusCode} - ${ex.responseBodyAsString}", ex)
        }
    }

    fun getAuthStatus(referenceNumber: String, tempToken: String): KsefAuthStatus {
        return restClient.get()
            .uri("$api/auth/$referenceNumber")
            .header("Authorization", "Bearer $tempToken")
            .retrieve()
            .body(KsefAuthStatus::class.java)
            ?: throw KsefApiException("Empty response from auth status")
    }

    fun redeemToken(tempToken: String): KsefAuthOperationStatusResponse {
        return try {
            restClient.post()
                .uri("$api/auth/token/redeem")
                .header("Authorization", "Bearer $tempToken")
                .header("Content-Type", "application/json")
                .retrieve()
                .body(KsefAuthOperationStatusResponse::class.java)
                ?: throw KsefApiException("Empty response from auth/token/redeem")
        } catch (ex: HttpClientErrorException) {
            log.error("KSeF token redeem failed: {} - {}", ex.statusCode, ex.responseBodyAsString)
            throw KsefApiException("KSeF token redeem failed: ${ex.statusCode}", ex)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Invoices
    // ─────────────────────────────────────────────────────────────────────

    fun queryInvoiceMetadata(
        pageOffset: Int,
        pageSize: Int,
        sortOrder: String = "ASC",
        filters: KsefInvoiceQueryFilters,
        accessToken: String
    ): KsefQueryInvoiceMetadataResponse {
        if (pageSize < 10 || pageSize > 250) {
            throw ValidationException("pageSize must be between 10 and 250, got $pageSize")
        }
        return try {
            restClient.post()
                .uri { ub ->
                    ub.path("$api/invoices/query/metadata")
                        .queryParam("pageOffset", pageOffset)
                        .queryParam("pageSize", pageSize)
                        .queryParam("sortOrder", sortOrder)
                        .build()
                }
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
                .body(filters)
                .retrieve()
                .body(KsefQueryInvoiceMetadataResponse::class.java)
                ?: KsefQueryInvoiceMetadataResponse(
                    invoices = emptyList(),
                    hasMore = false,
                    isTruncated = false,
                    permanentStorageHwmDate = null
                )
        } catch (ex: HttpClientErrorException) {
            log.error("KSeF invoice metadata query failed: {} - {}", ex.statusCode, ex.responseBodyAsString)
            throw KsefApiException("KSeF invoice query failed: ${ex.statusCode} - ${ex.responseBodyAsString}", ex)
        }
    }
}

class KsefApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
