// src/main/kotlin/pl/detailing/crm/config/GlobalExceptionHandler.kt

package pl.detailing.crm.config

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.gus.exception.CompanyNotFoundException
import pl.detailing.crm.gus.exception.GusServiceUnavailableException
import pl.detailing.crm.gus.exception.InvalidNipException
import pl.detailing.crm.shared.*
import java.time.Instant

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    private fun resolveContext(): String = try {
        val user = SecurityContextHelper.getCurrentUser()
        "studioId=${user.studioId.value}, userId=${user.userId.value}"
    } catch (_: Exception) {
        "studioId=unknown, userId=unknown"
    }

    @ExceptionHandler(AuthenticationException::class)
    fun handleAuthenticationException(ex: AuthenticationException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(ErrorResponse(
                error = "Brak autoryzacji",
                message = ex.message ?: "Wymagane uwierzytelnienie",
                timestamp = Instant.now().toString()
            ))
    }

    @ExceptionHandler(UnauthorizedException::class)
    fun handleUnauthorized(ex: UnauthorizedException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.UNAUTHORIZED)
            .body(ErrorResponse(
                error = "Brak autoryzacji",
                message = ex.message ?: "Wymagane uwierzytelnienie",
                timestamp = Instant.now().toString()
            ))
    }

    @ExceptionHandler(ForbiddenException::class)
    fun handleForbidden(ex: ForbiddenException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .body(ErrorResponse(
                error = "Brak dostępu",
                message = ex.message ?: "Nie masz uprawnień do wykonania tej operacji",
                timestamp = Instant.now().toString()
            ))
    }

    @ExceptionHandler(ValidationException::class)
    fun handleValidation(ex: ValidationException): ResponseEntity<ErrorResponse> {
        log.warn("ValidationException [{}]: {}", resolveContext(), ex.message)
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(
                error = "Błąd walidacji",
                message = ex.message ?: "Nieprawidłowe dane żądania",
                timestamp = Instant.now().toString()
            ))
    }

    @ExceptionHandler(EntityNotFoundException::class)
    fun handleNotFound(ex: EntityNotFoundException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(
                error = "Nie znaleziono",
                message = ex.message ?: "Żądany zasób nie istnieje",
                timestamp = Instant.now().toString()
            ))
    }

    @ExceptionHandler(ConflictException::class)
    fun handleConflict(ex: ConflictException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(ErrorResponse(
                error = "Konflikt danych",
                message = ex.message ?: "Operacja jest sprzeczna z aktualnym stanem zasobu",
                timestamp = Instant.now().toString()
            ))
    }

    @ExceptionHandler(UnprocessableEntityException::class)
    fun handleUnprocessableEntity(ex: UnprocessableEntityException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(ErrorResponse(
                error = "Nie można przetworzyć żądania",
                message = ex.message ?: "Żądanie jest poprawne składniowo, lecz nie można go wykonać",
                timestamp = Instant.now().toString()
            ))
    }

    @ExceptionHandler(InsufficientSmsCreditsException::class)
    fun handleInsufficientSmsCredits(ex: InsufficientSmsCreditsException): ResponseEntity<ErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.PAYMENT_REQUIRED)
            .body(ErrorResponse(
                error = "Niewystarczające kredyty SMS",
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
            ErrorResponse("Nieprawidłowy NIP", ex.message ?: "Podany numer NIP jest nieprawidłowy", Instant.now().toString())
        )

    @ExceptionHandler(CompanyNotFoundException::class)
    fun handleCompanyNotFound(ex: CompanyNotFoundException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ErrorResponse("Nie znaleziono firmy", ex.message ?: "Firma nie została znaleziona", Instant.now().toString())
        )

    @ExceptionHandler(GusServiceUnavailableException::class)
    fun handleGusUnavailable(ex: GusServiceUnavailableException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
            ErrorResponse("Usługa GUS niedostępna", ex.message ?: "Usługa GUS jest chwilowo niedostępna", Instant.now().toString())
        )

    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception): ResponseEntity<ErrorResponse> {
        log.error("Unhandled exception [{}]", resolveContext(), ex)
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse(
                error = "Błąd serwera",
                message = "Wystąpił nieoczekiwany błąd: ${ex.message}",
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