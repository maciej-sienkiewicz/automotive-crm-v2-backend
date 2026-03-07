// src/main/kotlin/pl/detailing/crm/config/GlobalExceptionHandler.kt

package pl.detailing.crm.config

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import pl.detailing.crm.invoicing.domain.*
import pl.detailing.crm.shared.*
import java.time.Instant

@RestControllerAdvice
class GlobalExceptionHandler {

    @ExceptionHandler(AuthenticationException::class)
    fun handleAuthenticationException(ex: AuthenticationException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(ErrorResponse(
                error = "Unauthorized",
                message = ex.message ?: "Authentication required",
                timestamp = Instant.now().toString()
            ))
    }

    @ExceptionHandler(UnauthorizedException::class)
    fun handleUnauthorized(ex: UnauthorizedException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(ErrorResponse(
                error = "Unauthorized",
                message = ex.message ?: "Authentication required",
                timestamp = Instant.now().toString()
            ))
    }

    @ExceptionHandler(ForbiddenException::class)
    fun handleForbidden(ex: ForbiddenException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .body(ErrorResponse(
                error = "Forbidden",
                message = ex.message ?: "Access denied",
                timestamp = Instant.now().toString()
            ))
    }

    @ExceptionHandler(ValidationException::class)
    fun handleValidation(ex: ValidationException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(
                error = "Validation Error",
                message = ex.message ?: "Invalid request",
                timestamp = Instant.now().toString()
            ))
    }

    @ExceptionHandler(EntityNotFoundException::class)
    fun handleNotFound(ex: EntityNotFoundException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(
                error = "Not Found",
                message = ex.message ?: "Resource not found",
                timestamp = Instant.now().toString()
            ))
    }

    @ExceptionHandler(ConflictException::class)
    fun handleConflict(ex: ConflictException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(ErrorResponse(
                error = "Conflict",
                message = ex.message ?: "Conflict",
                timestamp = Instant.now().toString()
            ))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Invoicing exceptions
    // ─────────────────────────────────────────────────────────────────────────

    @ExceptionHandler(InvoicingCredentialsNotFoundException::class)
    fun handleInvoicingCredentialsNotFound(ex: InvoicingCredentialsNotFoundException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(ErrorResponse(
                error     = "Invoicing Not Configured",
                message   = ex.message ?: "Brak konfiguracji integracji z dostawcą faktur",
                timestamp = Instant.now().toString()
            ))
    }

    @ExceptionHandler(InvoicingProviderApiException::class)
    fun handleInvoicingProviderApi(ex: InvoicingProviderApiException): ResponseEntity<InvoicingErrorResponse> {
        val httpStatus = when (ex.httpStatus) {
            401, 403 -> HttpStatus.UNPROCESSABLE_ENTITY   // credentials issue – 422 is more actionable than 401
            404      -> HttpStatus.NOT_FOUND
            422      -> HttpStatus.UNPROCESSABLE_ENTITY
            in 500..599 -> HttpStatus.BAD_GATEWAY
            else     -> HttpStatus.UNPROCESSABLE_ENTITY
        }
        return ResponseEntity
            .status(httpStatus)
            .body(InvoicingErrorResponse(
                error          = "Provider API Error",
                message        = ex.message ?: "Błąd zewnętrznego dostawcy faktur",
                providerErrors = ex.providerErrors,
                timestamp      = Instant.now().toString()
            ))
    }

    @ExceptionHandler(InvoicingValidationException::class)
    fun handleInvoicingValidation(ex: InvoicingValidationException): ResponseEntity<InvoicingErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(InvoicingErrorResponse(
                error          = "Invoicing Validation Error",
                message        = ex.message ?: "Błąd walidacji faktury",
                providerErrors = ex.errors,
                timestamp      = Instant.now().toString()
            ))
    }

    @ExceptionHandler(ExternalInvoiceNotFoundException::class)
    fun handleExternalInvoiceNotFound(ex: ExternalInvoiceNotFoundException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(
                error     = "Invoice Not Found",
                message   = ex.message ?: "Faktura nie istnieje",
                timestamp = Instant.now().toString()
            ))
    }

    @ExceptionHandler(InvoicingProviderNotSupportedException::class)
    fun handleInvoicingProviderNotSupported(ex: InvoicingProviderNotSupportedException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.NOT_IMPLEMENTED)
            .body(ErrorResponse(
                error     = "Provider Not Supported",
                message   = ex.message ?: "Dostawca nie jest obsługiwany",
                timestamp = Instant.now().toString()
            ))
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception): ResponseEntity<ErrorResponse> {
        ex.printStackTrace()
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse(
                error = "Internal Server Error",
                message = "An unexpected error occurred: ${ex.message}",
                timestamp = Instant.now().toString()
            ))
    }
}

data class ErrorResponse(
    val error: String,
    val message: String,
    val timestamp: String
)

/**
 * Extended error response for invoicing errors – includes provider-specific error messages
 * so the frontend can display the exact validation problems returned by the provider API.
 */
data class InvoicingErrorResponse(
    val error: String,
    val message: String,
    val providerErrors: List<String>,
    val timestamp: String
)