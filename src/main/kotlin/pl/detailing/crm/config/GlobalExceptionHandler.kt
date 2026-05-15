// src/main/kotlin/pl/detailing/crm/config/GlobalExceptionHandler.kt

package pl.detailing.crm.config

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import pl.detailing.crm.gus.exception.CompanyNotFoundException
import pl.detailing.crm.gus.exception.GusServiceUnavailableException
import pl.detailing.crm.gus.exception.InvalidNipException
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

    @ExceptionHandler(UnprocessableEntityException::class)
    fun handleUnprocessableEntity(ex: UnprocessableEntityException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(ErrorResponse(
                error = "Unprocessable Entity",
                message = ex.message ?: "Unprocessable entity",
                timestamp = Instant.now().toString()
            ))
    }

    @ExceptionHandler(InsufficientSmsCreditsException::class)
    fun handleInsufficientSmsCredits(ex: InsufficientSmsCreditsException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.PAYMENT_REQUIRED)
            .body(ErrorResponse(
                error = "Insufficient SMS Credits",
                message = ex.message ?: "Brak kredytów SMS",
                timestamp = Instant.now().toString()
            ))
    }

    /**
     * HTTP 402 — feature is not included in the studio's active plan.
     * Frontend uses this signal to render a paywall / demo mockup instead of an error screen.
     */
    @ExceptionHandler(FeatureLockedException::class)
    fun handleFeatureLocked(ex: FeatureLockedException): ResponseEntity<FeatureLockedResponse> {
        return ResponseEntity
            .status(HttpStatus.PAYMENT_REQUIRED)
            .body(
                FeatureLockedResponse(
                    feature = ex.featureKey.name,
                    featureDisplayName = ex.featureKey.displayName,
                    message = ex.message ?: "Ten moduł nie jest dostępny w Twoim planie."
                )
            )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // GUS integration exceptions
    // ─────────────────────────────────────────────────────────────────────────

    @ExceptionHandler(InvalidNipException::class)
    fun handleInvalidNip(ex: InvalidNipException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ErrorResponse("Invalid NIP", ex.message ?: "Nieprawidłowy numer NIP", Instant.now().toString())
        )

    @ExceptionHandler(CompanyNotFoundException::class)
    fun handleCompanyNotFound(ex: CompanyNotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ErrorResponse("Company Not Found", ex.message ?: "Firma nie została znaleziona", Instant.now().toString())
        )

    @ExceptionHandler(GusServiceUnavailableException::class)
    fun handleGusUnavailable(ex: GusServiceUnavailableException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
            ErrorResponse("GUS Service Unavailable", ex.message ?: "Usługa GUS jest chwilowo niedostępna", Instant.now().toString())
        )

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
 * Returned with HTTP 402 when a studio accesses a locked feature.
 * The [status] field is a stable string contract for frontend feature-gate logic.
 */
data class FeatureLockedResponse(
    val status: String = "FEATURE_LOCKED",
    val feature: String,
    val featureDisplayName: String,
    val message: String,
    val timestamp: String = Instant.now().toString()
)