package pl.detailing.crm.inbound

import pl.detailing.crm.shared.pii.Pii
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.UnauthorizedException
import pl.detailing.crm.shared.ValidationException
import java.security.MessageDigest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.inbound.accept.AcceptCallCommand
import pl.detailing.crm.inbound.accept.AcceptCallHandler
import pl.detailing.crm.inbound.register.RegisterInboundCallCommand
import pl.detailing.crm.inbound.register.RegisterInboundCallHandler
import pl.detailing.crm.inbound.reject.RejectCallCommand
import pl.detailing.crm.inbound.reject.RejectCallHandler
import pl.detailing.crm.inbound.update.UpdateCallCommand
import pl.detailing.crm.inbound.update.UpdateCallHandler
import pl.detailing.crm.shared.CallId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.studio.infrastructure.StudioRepository
import java.time.Instant

@RestController
@RequestMapping("/api/v1/inbound/calls")
class InboundController(
    private val registerInboundCallHandler: RegisterInboundCallHandler,
    private val updateCallHandler: UpdateCallHandler,
    private val acceptCallHandler: AcceptCallHandler,
    private val rejectCallHandler: RejectCallHandler,
    private val studioRepository: StudioRepository,
    /**
     * Shared secret for the telephony integration. Empty (the default) means the
     * anonymous path is switched off entirely — fail closed, like the platform key.
     */
    @Value("\${inbound.calls.webhook-secret:}") private val webhookSecret: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Register a new inbound call
     * POST /api/v1/inbound/calls
     *
     * Two callers are legitimate:
     *  - a logged-in CRM user (session) — the studio is the caller's own,
     *  - the telephony integration (no session, `SecurityConfig` permits the path) —
     *    it must present `X-Inbound-Secret` and name the target `studioId`.
     *
     * Before this check the endpoint was anonymous and wrote every call into
     * `studioRepository.findAll()[0]` — whichever tenant happened to sort first.
     */
    @PostMapping
    fun registerCall(
        @Valid @RequestBody request: RegisterCallRequest,
        @RequestHeader(name = HEADER_INBOUND_SECRET, required = false) presentedSecret: String?
    ): ResponseEntity<RegisterCallResponse> =
        runBlocking {
            val studioId = resolveStudio(request, presentedSecret)

            val command = RegisterInboundCallCommand(
                studioId = studioId,
                phoneNumber = request.phoneNumber,
                callerName = request.callerName,
                note = request.note,
                receivedAt = request.receivedAt ?: Instant.now()
            )

            val result = registerInboundCallHandler.handle(command)

            ResponseEntity.status(HttpStatus.CREATED).body(
                RegisterCallResponse(
                    id = result.leadId.toString(),
                    phoneNumber = result.phoneNumber,
                    contactName = result.callerName,
                    timestamp = result.receivedAt,
                    note = null
                )
            )
        }

    private fun resolveStudio(request: RegisterCallRequest, presentedSecret: String?): StudioId {
        // 1. Session caller: the studio is the principal's, full stop.
        val principal = runCatching { SecurityContextHelper.getCurrentUser() }.getOrNull()
        if (principal != null) return principal.studioId

        // 2. Webhook caller: configured secret, constant-time match, explicit existing studio.
        if (webhookSecret.isBlank()) {
            log.warn("Rejected anonymous inbound call — inbound.calls.webhook-secret is not configured")
            throw UnauthorizedException("Integracja telefoniczna nie została skonfigurowana")
        }
        if (presentedSecret == null || !constantTimeEquals(presentedSecret, webhookSecret)) {
            log.warn("Rejected anonymous inbound call — invalid {}", HEADER_INBOUND_SECRET)
            throw UnauthorizedException("Nieprawidłowy sekret integracji")
        }
        val studioId = request.studioId
            ?.let { runCatching { StudioId.fromString(it) }.getOrNull() }
            ?: throw ValidationException("studioId jest wymagane dla wywołania z integracji telefonicznej")
        if (!studioRepository.existsById(studioId.value)) {
            throw EntityNotFoundException("Studio nie istnieje")
        }
        return studioId
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    companion object {
        const val HEADER_INBOUND_SECRET = "X-Inbound-Secret"
    }

    /**
     * Update call information (contact name and note)
     * PATCH /api/v1/inbound/calls/{callId}
     */
    @PatchMapping("/{callId}")
    fun updateCall(
        @PathVariable callId: String,
        @RequestBody request: UpdateCallRequest
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = UpdateCallCommand(
            callId = CallId.fromString(callId),
            studioId = principal.studioId,
            callerName = request.contactName,
            note = request.note
        )

        updateCallHandler.handle(command)

        ResponseEntity.noContent().build()
    }

    /**
     * Accept an incoming call
     * POST /api/v1/inbound/calls/{callId}/accept
     */
    @PostMapping("/{callId}/accept")
    fun acceptCall(@PathVariable callId: String): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = AcceptCallCommand(
            callId = CallId.fromString(callId),
            studioId = principal.studioId,
            userId = principal.userId
        )

        acceptCallHandler.handle(command)

        ResponseEntity.noContent().build()
    }

    /**
     * Reject/dismiss an incoming call
     * POST /api/v1/inbound/calls/{callId}/reject
     */
    @PostMapping("/{callId}/reject")
    fun rejectCall(@PathVariable callId: String): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = RejectCallCommand(
            callId = CallId.fromString(callId),
            studioId = principal.studioId,
            userId = principal.userId
        )

        rejectCallHandler.handle(command)

        ResponseEntity.noContent().build()
    }
}

/**
 * Request/Response DTOs
 */
data class RegisterCallRequest(
    @field:NotBlank @field:Size(max = 32)
    val phoneNumber: String,
    @field:Size(max = 200)
    val callerName: String?,
    @field:Size(max = 2000)
    val note: String?,
    val receivedAt: Instant?, // ISO timestamp
    /**
     * Target studio — REQUIRED for the webhook caller, IGNORED for a session caller
     * (a logged-in user can never redirect a call into another studio).
     */
    @field:Size(max = 36)
    val studioId: String? = null
)

data class RegisterCallResponse(
    val id: String,
    @Pii val phoneNumber: String,
    @Pii val contactName: String?,
    val timestamp: Instant,
    val note: String?
)

data class UpdateCallRequest(
    val contactName: String?,
    val note: String?
)
