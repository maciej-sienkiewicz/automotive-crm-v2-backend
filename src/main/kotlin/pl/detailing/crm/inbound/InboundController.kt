package pl.detailing.crm.inbound

import kotlinx.coroutines.runBlocking
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
import java.time.Instant

@RestController
@RequestMapping("/api/v1/inbound/calls")
class InboundController(
    private val registerInboundCallHandler: RegisterInboundCallHandler,
    private val updateCallHandler: UpdateCallHandler,
    private val acceptCallHandler: AcceptCallHandler,
    private val rejectCallHandler: RejectCallHandler
) {

    /**
     * Register a new inbound call
     * POST /api/v1/inbound/calls
     */
    @PostMapping
    fun registerCall(@RequestBody request: RegisterCallRequest): ResponseEntity<RegisterCallResponse> =
        runBlocking {
            val principal = SecurityContextHelper.getCurrentUser()

            val command = RegisterInboundCallCommand(
                studioId = principal.studioId,
                phoneNumber = request.phoneNumber,
                callerName = request.callerName,
                note = request.note,
                receivedAt = request.receivedAt?.let { Instant.parse(it) } ?: Instant.now()
            )

            val result = registerInboundCallHandler.handle(command)

            ResponseEntity.status(HttpStatus.CREATED).body(
                RegisterCallResponse(
                    id = result.callId.toString(),
                    phoneNumber = result.phoneNumber,
                    contactName = result.callerName,
                    timestamp = result.receivedAt.toString(),
                    note = null
                )
            )
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
    val phoneNumber: String,
    val callerName: String?,
    val note: String?,
    val receivedAt: String? // ISO timestamp
)

data class RegisterCallResponse(
    val id: String,
    val phoneNumber: String,
    val contactName: String?,
    val timestamp: String,
    val note: String?
)

data class UpdateCallRequest(
    val contactName: String?,
    val note: String?
)
