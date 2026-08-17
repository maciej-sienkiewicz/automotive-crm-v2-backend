// src/main/kotlin/pl/detailing/crm/config/GlobalExceptionHandler.kt

package pl.detailing.crm.config

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.AuthenticationException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.gus.exception.CompanyNotFoundException
import pl.detailing.crm.gus.exception.GusServiceUnavailableException
import pl.detailing.crm.gus.exception.InvalidNipException
import pl.detailing.crm.security.TenantIsolationAuditService
import pl.detailing.crm.shared.*
import java.time.Instant

@RestControllerAdvice
class GlobalExceptionHandler(
    private val tenantIsolationAuditService: TenantIsolationAuditService
) {

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
    fun handleNotFound(ex: EntityNotFoundException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        try {
            val user = SecurityContextHelper.getCurrentUser()
            tenantIsolationAuditService.checkRequest(request, user)
        } catch (_: Exception) {
            // Not authenticated or audit check failed — swallow silently, return 404 regardless
        }
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(
                error = "Nie znaleziono",
                message = ex.message ?: "Żądany zasób nie istnieje",
                timestamp = Instant.now().toString()
            ))
    }

    @ExceptionHandler(NotFoundException::class)
    fun handleNotFoundBusiness(ex: NotFoundException): ResponseEntity<ErrorResponse> {
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

    @ExceptionHandler(VehiclePlateExistsException::class)
    fun handleVehiclePlateExists(ex: VehiclePlateExistsException): ResponseEntity<VehiclePlateExistsResponse> {
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(VehiclePlateExistsResponse(
                vehicleId = ex.vehicleId,
                brand = ex.brand,
                model = ex.model,
                year = ex.year,
                licensePlate = ex.licensePlate,
                primaryOwnerName = ex.primaryOwnerName,
                message = ex.message ?: "Pojazd o tym numerze rejestracyjnym już istnieje w bazie"
            ))
    }

    @ExceptionHandler(AlreadyLinkedException::class)
    fun handleAlreadyLinked(ex: AlreadyLinkedException): ResponseEntity<AlreadyLinkedResponse> {
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(AlreadyLinkedResponse(
                code = "ALREADY_LINKED",
                linkedLeadId = ex.linkedLeadId,
                linkedLeadName = ex.linkedLeadName
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

    /**
     * HTTP 402 — the studio owns the module but has run out of SMS credits.
     * Distinguished from a missing module by [PaywallErrorResponse.code]:
     * INSUFFICIENT_CREDITS → "top up credits", MODULE_REQUIRED → "buy the module".
     */
    @ExceptionHandler(InsufficientSmsCreditsException::class)
    fun handleInsufficientSmsCredits(ex: InsufficientSmsCreditsException): ResponseEntity<PaywallErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.PAYMENT_REQUIRED)
            .body(PaywallErrorResponse(
                code = PaywallErrorResponse.CODE_INSUFFICIENT_CREDITS,
                error = "Niewystarczające kredyty SMS",
                message = ex.message ?: "Brak kredytów SMS"
            ))
    }

    /**
     * HTTP 402 — the action's capability expression is not satisfied by the studio's
     * entitlements. The payload is checkout-ready: it names the exact missing
     * features and the add-ons that provide them, so the frontend renders a
     * precise upsell surface instead of a generic error.
     */
    @ExceptionHandler(CapabilityLockedException::class)
    fun handleCapabilityLocked(ex: CapabilityLockedException): ResponseEntity<PaywallErrorResponse> {
        return ResponseEntity
            .status(HttpStatus.PAYMENT_REQUIRED)
            .body(PaywallErrorResponse(
                code = PaywallErrorResponse.CODE_MODULE_REQUIRED,
                error = "Moduł niedostępny w Twoim planie",
                message = ex.message ?: "Ta funkcja wymaga dodatkowego modułu.",
                capability = ex.capability.name,
                capabilityDisplayName = ex.capability.displayName,
                missingFeatures = ex.missingFeatures.map {
                    MissingFeatureDto(key = it.name, displayName = it.displayName)
                },
                upsell = ex.upsell.map {
                    PaywallUpsellOptionDto(
                        addOnKey = it.addOnKey,
                        addOnName = it.addOnName,
                        monthlyPriceGrossCents = it.monthlyPriceGrossCents,
                        isAvailable = it.isAvailable
                    )
                }
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

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrityViolation(ex: DataIntegrityViolationException): ResponseEntity<ErrorResponse> {
        val rawMessage = ex.message ?: ""
        val userMessage = CONSTRAINT_MESSAGES.entries
            .firstOrNull { (constraint, _) -> rawMessage.contains(constraint) }
            ?.value
            ?: "Operacja nie może zostać wykonana — naruszenie unikalności danych."
        log.warn("DataIntegrityViolationException [{}]: {}", resolveContext(), rawMessage)
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(ErrorResponse(
                error = "Konflikt danych",
                message = userMessage,
                timestamp = Instant.now().toString()
            ))
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneric(ex: Exception): ResponseEntity<ErrorResponse> {
        log.error("Unhandled exception [{}]", resolveContext(), ex)
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse(
                error = "Błąd serwera",
                message = "Wystąpił nieoczekiwany błąd. Spróbuj ponownie lub skontaktuj się z pomocą techniczną.",
                timestamp = Instant.now().toString()
            ))
    }

    companion object {
        private val CONSTRAINT_MESSAGES = mapOf(
            "idx_customers_studio_phone" to "Klient z podanym numerem telefonu już istnieje w tym studiu.",
            "idx_customers_studio_email" to "Klient z podanym adresem e-mail już istnieje w tym studiu.",
            "idx_users_studio_email" to "Użytkownik z podanym adresem e-mail już istnieje.",
            "idx_users_mobile_token" to "Podany token urządzenia mobilnego jest już przypisany.",
            "idx_studio_roles_studio_name" to "Rola o podanej nazwie już istnieje w tym studiu.",
            "idx_studios_email_alias" to "Podany alias e-mail jest już zajęty.",
            "idx_visits_visit_number" to "Wizyta o podanym numerze już istnieje.",
            "idx_sms_credit_balances_studio_id" to "Saldo kredytów SMS dla tego studia zostało już zainicjalizowane.",
            "idx_sms_automation_configs_studio_id" to "Konfiguracja automatyzacji SMS dla tego studia już istnieje.",
            "idx_email_automation_configs_studio_id" to "Konfiguracja automatyzacji e-mail dla tego studia już istnieje.",
            "idx_demo_accounts_studio_id" to "Konto demo dla tego studia już istnieje.",
            "idx_lead_estimations_lead_id" to "Wycena dla tego zapytania już istnieje.",
            "idx_lead_user_quotes_lead_id" to "Oferta użytkownika dla tego zapytania już istnieje.",
            "idx_studio_ig_studio_profile" to "Profil Instagram dla tego studia jest już połączony.",
            "idx_ig_profiles_username" to "Profil Instagram o podanej nazwie użytkownika już istnieje.",
            "idx_ig_posts_post_pk" to "Post Instagram o podanym identyfikatorze już istnieje.",
            "idx_ig_reactions_studio_post" to "Reakcja studia na ten post już istnieje.",
            "idx_ig_stories_story_id" to "Story Instagram o podanym identyfikatorze już istnieje.",
            "idx_protocol_mappings_unique" to "Mapowanie pola protokołu już istnieje.",
            "idx_consent_templates_def_version" to "Szablon zgody w tej wersji już istnieje.",
            "idx_sms_send_log_appointment_trigger" to "Wiadomość SMS dla tej wizyty i zdarzenia została już wysłana."
        )
    }
}

data class ErrorResponse(
    val error: String,
    val message: String,
    val timestamp: String
)

data class AlreadyLinkedResponse(
    val code: String,
    val linkedLeadId: String,
    val linkedLeadName: String?
)

/**
 * Returned with HTTP 409 when creating a vehicle whose license plate already exists.
 * Carries enough of the existing vehicle for the frontend to render the collision
 * card (link as co-owner / transfer / it's a different vehicle) without a second request.
 */
data class VehiclePlateExistsResponse(
    val code: String = "VEHICLE_PLATE_EXISTS",
    val vehicleId: String,
    val brand: String,
    val model: String,
    val year: Int?,
    val licensePlate: String?,
    val primaryOwnerName: String?,
    val message: String
)

/**
 * Returned with HTTP 402 when a studio accesses a locked feature.
 * The [status] field is a stable string contract for frontend feature-gate logic.
 *
 * Legacy shape kept for the deprecated @RequiresFeature path; new code throws
 * CapabilityLockedException and gets [PaywallErrorResponse] instead.
 */
data class FeatureLockedResponse(
    val status: String = "FEATURE_LOCKED",
    val code: String = PaywallErrorResponse.CODE_MODULE_REQUIRED,
    val feature: String,
    val featureDisplayName: String,
    val message: String,
    val timestamp: String = Instant.now().toString()
)

/**
 * The single 402 contract for the frontend. Every HTTP 402 body carries a
 * machine-readable [code]; the frontend MUST branch on it, never on the message:
 *  - [CODE_MODULE_REQUIRED]      → render the module upsell (fields below populated)
 *  - [CODE_INSUFFICIENT_CREDITS] → render the "top up SMS credits" prompt
 */
data class PaywallErrorResponse(
    val code: String,
    val error: String,
    val message: String,
    val capability: String? = null,
    val capabilityDisplayName: String? = null,
    val missingFeatures: List<MissingFeatureDto> = emptyList(),
    val upsell: List<PaywallUpsellOptionDto> = emptyList(),
    val timestamp: String = Instant.now().toString()
) {
    companion object {
        const val CODE_MODULE_REQUIRED = "MODULE_REQUIRED"
        const val CODE_INSUFFICIENT_CREDITS = "INSUFFICIENT_CREDITS"
    }
}

data class MissingFeatureDto(
    val key: String,
    val displayName: String
)

data class PaywallUpsellOptionDto(
    val addOnKey: String,
    val addOnName: String,
    val monthlyPriceGrossCents: Long?,
    val isAvailable: Boolean
)