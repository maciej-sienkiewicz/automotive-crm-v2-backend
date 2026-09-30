package pl.detailing.crm.employee.leaverequest

import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.employee.leaverequest.cancel.CancelLeaveRequestCommand
import pl.detailing.crm.employee.leaverequest.cancel.CancelLeaveRequestHandler
import pl.detailing.crm.employee.leaverequest.decide.DecideLeaveRequestCommand
import pl.detailing.crm.employee.leaverequest.decide.DecideLeaveRequestHandler
import pl.detailing.crm.employee.leaverequest.query.LeaveRequestAccess
import pl.detailing.crm.employee.leaverequest.query.LeaveRequestQueryService
import pl.detailing.crm.employee.leaverequest.query.LeaveRequestQueueFilter
import pl.detailing.crm.employee.leaverequest.session.LeaveSigningSessionService
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.security.ClientIpResolver
import pl.detailing.crm.shared.ValidationException
import java.util.UUID

/**
 * Rozpatrywanie wniosków urlopowych zespołu (`docs/api-leave-requests.md`).
 *
 * Adnotacja na klasie wpuszcza właściciela i osoby z EMPLOYEES_LEAVES_APPROVE. To pierwsza
 * warstwa — druga jest w handlerach: [pl.detailing.crm.employee.leaverequest.domain.LeaveApprovalPolicy]
 * w chwili decyzji zna zakaz rozpatrzenia własnego wniosku i widzi rolę odebraną w trakcie.
 */
@RestController
@RequestMapping("/api/v1/leave-requests")
@RequiresPermission(Permission.EMPLOYEES_LEAVES_APPROVE)
class LeaveRequestApprovalController(
    private val queries: LeaveRequestQueryService,
    private val access: LeaveRequestAccess,
    private val presenter: LeaveRequestPresenter,
    private val pdfResponses: LeaveRequestPdfResponses,
    private val sessions: LeaveSigningSessionService,
    private val decideHandler: DecideLeaveRequestHandler,
    private val cancelHandler: CancelLeaveRequestHandler,
    @Value("\${security.rate-limit.trusted-proxies:}") trustedProxies: String
) {
    private val clientIpResolver = ClientIpResolver(trustedProxies.split(","))

    @GetMapping
    fun list(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) employeeId: UUID?
    ): ResponseEntity<LeaveRequestQueueResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val filter = status?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            LeaveRequestQueueFilter.entries.firstOrNull { it.name == raw.uppercase() }
                ?: throw ValidationException("Nieznany filtr statusu: $raw", field = "status")
        } ?: LeaveRequestQueueFilter.PENDING
        return ResponseEntity.ok(
            LeaveRequestQueueResponse(
                items = presenter.summaries(principal.studioId.value, queries.queue(principal.studioId, filter, employeeId)),
                pendingCount = queries.pendingInStudio(principal.studioId)
            )
        )
    }

    /** Wnioski, które TEN użytkownik może rozpatrzyć (bez własnych) — licznik przy „Pracownicy". */
    @GetMapping("/pending-count")
    fun pendingCount(): ResponseEntity<LeaveRequestPendingCountResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(LeaveRequestPendingCountResponse(queries.pendingDecidableBy(principal)))
    }

    @GetMapping("/{id}")
    fun detail(@PathVariable id: UUID): ResponseEntity<LeaveRequestDetailResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(presenter.detail(principal, access.submittedRequest(principal.studioId, id)))
    }

    /** Sesja podpisu decyzji — skrót wersji podpisanej przez pracownika (H2) i jednorazowy token. */
    @PostMapping("/{id}/decision-session")
    fun decisionSession(@PathVariable id: UUID): ResponseEntity<SigningSessionResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(sessions.forDecision(principal, id).toResponse())
    }

    /** Wersja podpisana przez pracownika — dokładnie to, co podpisuje rozpatrujący. */
    @GetMapping("/{id}/document")
    fun document(@PathVariable id: UUID): ResponseEntity<ByteArray> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val request = access.submittedRequest(principal.studioId, id)
        pdfResponses.forSigning(request, request.employeeSignedPdfS3Key, request.employeeSignedSha256)
    }

    @PostMapping("/{id}/approve")
    fun approve(
        @PathVariable id: UUID,
        @RequestBody body: LeaveDecisionRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<LeaveRequestDetailResponse> = decide(id, body, approve = true, httpRequest)

    @PostMapping("/{id}/reject")
    fun reject(
        @PathVariable id: UUID,
        @RequestBody body: LeaveDecisionRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<LeaveRequestDetailResponse> = decide(id, body, approve = false, httpRequest)

    @PostMapping("/{id}/cancel")
    fun cancel(
        @PathVariable id: UUID,
        @RequestBody body: CancelLeaveRequestRequest
    ): ResponseEntity<LeaveRequestDetailResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val cancelled = cancelHandler.handle(CancelLeaveRequestCommand(principal, id, body.reason))
        ResponseEntity.ok(presenter.detail(principal, cancelled))
    }

    @GetMapping("/{id}/file")
    fun file(@PathVariable id: UUID): ResponseEntity<ByteArray> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        pdfResponses.current(access.submittedRequest(principal.studioId, id))
    }

    private fun decide(
        id: UUID,
        body: LeaveDecisionRequest,
        approve: Boolean,
        httpRequest: HttpServletRequest
    ): ResponseEntity<LeaveRequestDetailResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val decided = decideHandler.handle(
            DecideLeaveRequestCommand(
                principal = principal,
                requestId = id,
                approve = approve,
                signatureImageBase64 = body.signatureImageBase64,
                useSavedSignature = body.useSavedSignature == true,
                documentSha256 = body.documentSha256,
                challenge = body.challenge,
                note = body.note,
                ipAddress = clientIpResolver.resolve(httpRequest),
                userAgent = httpRequest.getHeader(HttpHeaders.USER_AGENT)
            )
        )
        ResponseEntity.ok(presenter.detail(principal, decided))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Request / Response DTOs (wspólne typy wniosku: MyLeaveRequestController.kt)
// ─────────────────────────────────────────────────────────────────────────────

data class LeaveDecisionRequest(
    val signatureImageBase64: String? = null,
    val useSavedSignature: Boolean? = false,
    val documentSha256: String? = null,
    val challenge: String? = null,
    val note: String? = null
)

data class CancelLeaveRequestRequest(val reason: String? = null)

data class LeaveRequestQueueResponse(
    val items: List<LeaveRequestSummaryResponse>,
    val pendingCount: Int
)

data class LeaveRequestPendingCountResponse(val count: Int)
