package pl.detailing.crm.trends.exception

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(basePackages = ["pl.detailing.crm.trends"])
class TrendsExceptionHandler {

    private val log = LoggerFactory.getLogger(TrendsExceptionHandler::class.java)

    data class ErrorResponse(val error: String, val message: String?, val statusCode: Int? = null)

    @ExceptionHandler(TrendsValidationException::class)
    fun handleValidation(ex: TrendsValidationException): ResponseEntity<ErrorResponse> {
        log.warn("Validation error: {}", ex.message)
        return ResponseEntity.badRequest()
            .body(ErrorResponse("VALIDATION_ERROR", ex.message, HttpStatus.BAD_REQUEST.value()))
    }

    @ExceptionHandler(DataForSeoAuthException::class)
    fun handleAuth(ex: DataForSeoAuthException): ResponseEntity<ErrorResponse> {
        log.error("DataForSEO auth error: {}", ex.message)
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ErrorResponse("AUTH_ERROR", ex.message, ex.statusCode))
    }

    @ExceptionHandler(TrendsRateLimitException::class)
    fun handleRateLimit(ex: TrendsRateLimitException): ResponseEntity<ErrorResponse> {
        log.warn("DataForSEO rate limit: {}", ex.message)
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .body(ErrorResponse("RATE_LIMIT", ex.message, ex.statusCode))
    }

    @ExceptionHandler(DataForSeoApiException::class)
    fun handleApiError(ex: DataForSeoApiException): ResponseEntity<ErrorResponse> {
        log.error("DataForSEO API error: {}", ex.message, ex)
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(ErrorResponse("API_ERROR", ex.message, ex.statusCode))
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception): ResponseEntity<ErrorResponse> {
        log.error("Unexpected error in Trends module", ex)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse("INTERNAL_ERROR", ex.message))
    }
}
