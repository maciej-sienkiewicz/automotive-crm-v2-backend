package pl.detailing.crm.invoicing.adapter.infakt

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClient
import pl.detailing.crm.invoicing.adapter.infakt.dto.*
import pl.detailing.crm.invoicing.domain.InvoiceProviderType
import pl.detailing.crm.invoicing.domain.InvoicingProviderApiException

/**
 * Low-level HTTP client for the inFakt REST API v3.
 *
 * Authentication: X-inFakt-ApiKey header on every request.
 * Base URL: https://api.infakt.pl
 *
 * All methods throw [InvoicingProviderApiException] on non-2xx responses,
 * with error messages extracted from the inFakt error response body.
 */
@Component
class InfaktApiClient(
    private val objectMapper: ObjectMapper
) {
    companion object {
        const val BASE_URL = "https://api.sandbox-infakt.pl/api"
        const val API_KEY_HEADER = "X-inFakt-ApiKey"
        const val PAGE_SIZE = 100
        private val log = LoggerFactory.getLogger(InfaktApiClient::class.java)
    }

    private val restClient: RestClient = RestClient.builder()
        .baseUrl(BASE_URL)
        .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
        .build()

    /**
     * POST /v3/invoices.json – issue a new invoice.
     */
    fun createInvoice(apiKey: String, request: InfaktCreateInvoiceRequest): InfaktInvoiceDto {
        return executeWithErrorHandling(apiKey) {
            restClient.post()
                .uri("/v3/invoices.json")
                .header(API_KEY_HEADER, apiKey)
                .body(request)
                .retrieve()
                .body(InfaktInvoiceDto::class.java)
                ?: throw InvoicingProviderApiException("Pusta odpowiedź z inFakt API przy tworzeniu faktury", 500)
        }
    }

    /**
     * GET /v3/invoices/{id}.json – fetch a single invoice by ID.
     */
    fun getInvoice(apiKey: String, invoiceId: String): InfaktInvoiceDto {
        return executeWithErrorHandling(apiKey) {
            restClient.get()
                .uri("/v3/invoices/{id}.json", invoiceId)
                .header(API_KEY_HEADER, apiKey)
                .retrieve()
                .body(InfaktInvoiceDto::class.java)
                ?: throw InvoicingProviderApiException("Pusta odpowiedź z inFakt API przy pobieraniu faktury $invoiceId", 500)
        }
    }

    /**
     * GET /v3/invoices.json – paginated list of invoices.
     *
     * @param page 1-based page number (converted to offset internally).
     */
    fun listInvoices(apiKey: String, page: Int = 1, perPage: Int = PAGE_SIZE): InfaktInvoiceListResponse {
        val offset = (page - 1) * perPage
        log.info("[InFakt] listInvoices: offset={}, limit={}", offset, perPage)

        return executeWithErrorHandling(apiKey) {
            val rawBody = restClient.get()
                .uri { builder ->
                    builder.path("/v3/invoices.json")
                        .queryParam("offset", offset)
                        .queryParam("limit", perPage)
                        .build()
                }
                .header(API_KEY_HEADER, apiKey)
                .retrieve()
                .body(String::class.java)
                ?: ""

            log.info("[InFakt] listInvoices raw response (first 500 chars): {}", rawBody.take(500))

            try {
                val parsed = objectMapper.readValue(rawBody, InfaktInvoiceListResponse::class.java)
                log.info(
                    "[InFakt] listInvoices parsed: entities={}, metainfo.total_count={}, metainfo.count={}",
                    parsed.entities?.size,
                    parsed.metaData?.total,
                    parsed.metaData?.count
                )
                parsed
            } catch (ex: Exception) {
                log.error("[InFakt] listInvoices deserialization FAILED. Body: {}", rawBody, ex)
                throw ex
            }
        }
    }

    /**
     * GET /v3/invoices.json?per_page=1 – lightweight verification call.
     *
     * Returns HTTP 200 for a valid key, HTTP 401 for an invalid key.
     * Does NOT throw – returns null on network/server error so the caller can
     * decide how to handle connectivity issues separately from auth failures.
     *
     * @return true = key is valid, false = key is invalid (401/403), null = couldn't connect
     */
    fun ping(apiKey: String): Boolean? {
        return try {
            restClient.get()
                .uri { builder ->
                    builder.path("/v3/invoices.json")
                        .queryParam("per_page", 1)
                        .build()
                }
                .header(API_KEY_HEADER, apiKey)
                .retrieve()
                .toBodilessEntity()
            true
        } catch (ex: HttpClientErrorException) {
            when (ex.statusCode) {
                HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN -> false
                else -> null
            }
        } catch (_: HttpServerErrorException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun <T> executeWithErrorHandling(apiKey: String, block: () -> T): T {
        return try {
            block()
        } catch (ex: HttpClientErrorException) {
            val errors = parseErrorResponse(ex.responseBodyAsString)
            when (ex.statusCode) {
                HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN ->
                    throw InvoicingProviderApiException.unauthorized(InvoiceProviderType.INFAKT)
                HttpStatus.NOT_FOUND ->
                    throw InvoicingProviderApiException("Zasób nie został znaleziony w inFakt API", 404)
                HttpStatus.UNPROCESSABLE_ENTITY, HttpStatus.BAD_REQUEST ->
                    throw InvoicingProviderApiException.validationFailed(InvoiceProviderType.INFAKT, errors)
                else ->
                    throw InvoicingProviderApiException(
                        "Błąd wywołania inFakt API (HTTP ${ex.statusCode.value()}): ${errors.joinToString("; ")}",
                        ex.statusCode.value(),
                        errors
                    )
            }
        } catch (ex: HttpServerErrorException) {
            throw InvoicingProviderApiException.serverError(InvoiceProviderType.INFAKT, ex.statusCode.value())
        } catch (ex: InvoicingProviderApiException) {
            throw ex
        } catch (ex: Exception) {
            log.error("[InFakt] Unexpected error communicating with inFakt API", ex)
            throw InvoicingProviderApiException(
                "Nieoczekiwany błąd podczas komunikacji z inFakt: ${ex.message}",
                500
            )
        }
    }

    private fun parseErrorResponse(body: String): List<String> {
        return try {
            objectMapper.readValue(body, InfaktErrorResponse::class.java).toErrorMessages()
        } catch (_: Exception) {
            if (body.isNotBlank()) listOf(body.take(300)) else listOf("Nieznany błąd API")
        }
    }
}
