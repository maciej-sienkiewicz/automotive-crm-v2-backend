// src/main/kotlin/pl/detailing/crm/config/GlobalExceptionHandler.kt

package pl.detailing.crm.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.core.AuthenticationException
import org.springframework.validation.BindException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.servlet.resource.NoResourceFoundException
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.gus.exception.CompanyNotFoundException
import pl.detailing.crm.gus.exception.GusServiceUnavailableException
import pl.detailing.crm.gus.exception.InvalidNipException
import pl.detailing.crm.leads.similar.PendingSuggestionPriceException
import pl.detailing.crm.security.TenantIsolationAuditService
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.domain.IllegalStateTransitionException
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

    // ─────────────────────────────────────────────────────────────────────────
    // Client-side input errors → 400. Without these handlers every malformed body,
    // failed @Valid constraint, bad enum/UUID/int in a path or query parameter and
    // every `require(...)` in a handler fell through to the generic 500 below — a
    // wrong status for the client and an ERROR-level stack trace per request that
    // anyone could trigger at will.
    // ─────────────────────────────────────────────────────────────────────────

    /** `@Valid @RequestBody` failures (MethodArgumentNotValidException extends BindException). */
    @ExceptionHandler(BindException::class)
    fun handleBeanValidation(ex: BindException): ResponseEntity<ValidationErrorResponse> {
        val fieldErrors = ex.bindingResult.fieldErrors.map { fe ->
            FieldErrorDto(field = fe.field, message = fe.defaultMessage ?: "Nieprawidłowa wartość")
        }
        val globalErrors = ex.bindingResult.globalErrors.map { ge ->
            FieldErrorDto(field = ge.objectName, message = ge.defaultMessage ?: "Nieprawidłowa wartość")
        }
        log.warn("Bean validation failed [{}]: {}", resolveContext(), fieldErrors + globalErrors)
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ValidationErrorResponse(
                error = "Błąd walidacji",
                message = "Nieprawidłowe dane żądania",
                fieldErrors = fieldErrors + globalErrors,
                timestamp = Instant.now().toString()
            ))
    }

    /** `@Validated` on request params / path variables. */
    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(ex: ConstraintViolationException): ResponseEntity<ValidationErrorResponse> {
        val errors = ex.constraintViolations.map { cv ->
            FieldErrorDto(field = cv.propertyPath.toString(), message = cv.message ?: "Nieprawidłowa wartość")
        }
        log.warn("Constraint violation [{}]: {}", resolveContext(), errors)
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ValidationErrorResponse(
                error = "Błąd walidacji",
                message = "Nieprawidłowe dane żądania",
                fieldErrors = errors,
                timestamp = Instant.now().toString()
            ))
    }

    /**
     * Malformed / unparsable JSON body, type mismatch in a path or query parameter
     * (`?page=abc`, `{id}` that is not a UUID, unknown enum constant), missing required
     * parameter. The framework message is deliberately NOT echoed back — it names
     * internal classes and constructor signatures.
     */
    @ExceptionHandler(
        HttpMessageNotReadableException::class,
        MethodArgumentTypeMismatchException::class,
        MissingServletRequestParameterException::class
    )
    fun handleMalformedRequest(ex: Exception): ResponseEntity<ErrorResponse> {
        log.warn("Malformed request [{}]: {}", resolveContext(), ex.message?.lineSequence()?.firstOrNull())
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(
                error = "Nieprawidłowe żądanie",
                message = "Żądanie zawiera nieprawidłowe lub brakujące dane",
                timestamp = Instant.now().toString()
            ))
    }

    /**
     * `require(...)` / `UUID.fromString` / value-class invariants (`Money` ≥ 0) hit by
     * client input. Generic message on purpose: `IllegalArgumentException` messages are
     * written for developers and can carry internal state.
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(ex: IllegalArgumentException): ResponseEntity<ErrorResponse> {
        log.warn("IllegalArgumentException [{}]: {}", resolveContext(), ex.message)
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(
                error = "Nieprawidłowe żądanie",
                message = "Żądanie zawiera nieprawidłowe dane",
                timestamp = Instant.now().toString()
            ))
    }

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun handleUploadTooLarge(ex: MaxUploadSizeExceededException): ResponseEntity<ErrorResponse> {
        log.warn("Upload too large [{}]: {}", resolveContext(), ex.message)
        return ResponseEntity
            .status(HttpStatus.PAYLOAD_TOO_LARGE)
            .body(ErrorResponse(
                error = "Plik zbyt duży",
                message = "Przesłany plik przekracza dopuszczalny rozmiar",
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

    /**
     * 404 — bo dla użytkownika wizyty w tym stanie po prostu nie ma. Kod pozwala
     * frontendowi zamiast surowego błędu pokazać jedyną sensowną tu akcję:
     * dokończenie przyjęcia pojazdu.
     */
    @ExceptionHandler(VisitNotStartedException::class)
    fun handleVisitNotStarted(ex: VisitNotStartedException): ResponseEntity<VisitNotStartedResponse> {
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(VisitNotStartedResponse(
                code = "VISIT_NOT_STARTED",
                visitId = ex.visitId,
                visitNumber = ex.visitNumber,
                message = ex.message ?: "Wizyta nie została jeszcze rozpoczęta"
            ))
    }

    /**
     * 409 z namiarem na istniejący szkic: kreator ma go wznowić, a nie założyć drugą
     * wizytę dla tego samego auta.
     */
    @ExceptionHandler(DraftVisitAlreadyExistsException::class)
    fun handleDraftVisitAlreadyExists(
        ex: DraftVisitAlreadyExistsException
    ): ResponseEntity<DraftVisitAlreadyExistsResponse> {
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(DraftVisitAlreadyExistsResponse(
                code = "DRAFT_VISIT_ALREADY_EXISTS",
                visitId = ex.visitId,
                visitNumber = ex.visitNumber,
                createdAt = ex.createdAt.toString(),
                createdByName = ex.createdByName,
                message = ex.message ?: "Trwa już nieukończone przyjęcie tej rezerwacji"
            ))
    }

    /** Domain state-machine refusals (visit transitions, frozen line items) are conflicts, not crashes. */
    @ExceptionHandler(IllegalStateTransitionException::class)
    fun handleIllegalStateTransition(ex: IllegalStateTransitionException): ResponseEntity<ErrorResponse> {
        log.warn("IllegalStateTransition [{}]: {}", resolveContext(), ex.message)
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(ErrorResponse(
                error = "Konflikt stanu",
                message = ex.message ?: "Operacja jest sprzeczna z aktualnym stanem zasobu",
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

    /**
     * Rezerwacji nie wolno założyć z sugestiami czekającymi na kwotę. 409 niesie ICH
     * NAZWY, żeby interfejs wymusił kwoty na tych konkretnych pozycjach.
     */
    @ExceptionHandler(PendingSuggestionPriceException::class)
    fun handlePendingSuggestionPrice(ex: PendingSuggestionPriceException): ResponseEntity<PendingSuggestionPriceResponse> {
        return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(PendingSuggestionPriceResponse(
                error = "Sugestie bez ceny",
                serviceNames = ex.serviceNames,
                message = "Podaj kwotę dla: ${ex.serviceNames.joinToString(", ")}"
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

    /**
     * Żądanie pod adres, którego nie obsługuje żaden kontroler.
     *
     * Bez tego handlera Spring oddaje takie żądanie `ResourceHttpRequestHandler`-owi, ten
     * rzuca [NoResourceFoundException], a łapie ją dopiero `handleGeneric` — czyli 500 ze
     * stacktrace na poziomie ERROR. Klient wołający usunięty endpoint (np. starszy build
     * frontendu po wyburzeniu modułu metryk) zalewał wtedy log setkami rzekomych awarii
     * serwera, maskując prawdziwe błędy. To jest 404: żądanie jest złe, nie serwer.
     */
    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResource(ex: NoResourceFoundException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        log.debug("No handler for {} {} [{}]", request.method, request.requestURI, resolveContext())
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(ErrorResponse(
                error = "Nie znaleziono",
                message = "Żądany zasób nie istnieje",
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

/** 400 z listą pól, które nie przeszły walidacji JSR-380. */
data class ValidationErrorResponse(
    val error: String,
    val message: String,
    val fieldErrors: List<FieldErrorDto>,
    val timestamp: String
)

data class FieldErrorDto(
    val field: String,
    val message: String
)

/**
 * 404 dla wizyty, która nie wyszła jeszcze ze stanu roboczego przyjęcia.
 */
data class VisitNotStartedResponse(
    val code: String,
    val visitId: String,
    val visitNumber: String,
    val message: String
)

/**
 * 409 przy próbie przyjęcia rezerwacji, dla której trwa już nieukończone przyjęcie.
 * Niesie namiar na istniejący szkic, żeby frontend mógł go wznowić bez dopytywania.
 */
data class DraftVisitAlreadyExistsResponse(
    val code: String,
    val visitId: String,
    val visitNumber: String,
    val createdAt: String,
    val createdByName: String?,
    val message: String
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
data class PendingSuggestionPriceResponse(
    val error: String,
    val serviceNames: List<String>,
    val message: String
)

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