package pl.detailing.crm.employee.leaverequest

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Value
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
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
import pl.detailing.crm.employee.leaverequest.create.CreateLeaveRequestCommand
import pl.detailing.crm.employee.leaverequest.create.CreateLeaveRequestHandler
import pl.detailing.crm.employee.leaverequest.decide.DecideLeaveRequestHandler
import pl.detailing.crm.employee.leaverequest.query.LeaveRequestAccess
import pl.detailing.crm.employee.leaverequest.query.LeaveRequestQueryService
import pl.detailing.crm.employee.leaverequest.query.LeaveRequestQueueFilter
import pl.detailing.crm.employee.leaverequest.session.LeaveSigningSessionService
import pl.detailing.crm.employee.leaverequest.submit.SubmitLeaveRequestCommand
import pl.detailing.crm.employee.leaverequest.submit.SubmitLeaveRequestHandler
import pl.detailing.crm.employee.leaverequest.withdraw.WithdrawLeaveRequestHandler
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.security.ClientIpResolver
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.ValidationException
import java.time.LocalDate
import java.util.UUID

/**
 * Rozpatrywanie wniosków urlopowych zespołu (`docs/api-leave-requests.md`) i urlop
 * dodany przez administratora w imieniu pracownika (ON_BEHALF, „Zmiany z 01.10.2026 (v2)").
 *
 * Adnotacja na klasie wpuszcza właściciela i osoby z EMPLOYEES_LEAVES_APPROVE. To pierwsza
 * warstwa — druga jest w handlerach: [pl.detailing.crm.employee.leaverequest.domain.LeaveApprovalPolicy]
 * w chwili decyzji zna zakaz rozpatrzenia własnego wniosku i widzi rolę odebraną w trakcie,
 * a [LeaveRequestAccess.employeeForOnBehalf] nie pozwala wprowadzić „w imieniu" wniosku
 * dla samego siebie.
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
    private val createHandler: CreateLeaveRequestHandler,
    private val submitHandler: SubmitLeaveRequestHandler,
    private val withdrawHandler: WithdrawLeaveRequestHandler,
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

    /**
     * Administrator wprowadza wniosek w imieniu pracownika: szkic (H1) i sesja podpisu
     * osobistego. Te same reguły co samoobsługa — to ten sam handler.
     */
    @PostMapping
    fun create(@RequestBody body: OnBehalfLeaveRequestRequest): ResponseEntity<CreateLeaveRequestResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val result = createHandler.handle(
            CreateLeaveRequestCommand(
                studioId = principal.studioId,
                userId = principal.userId,
                userName = principal.fullName,
                onBehalfOfEmployeeId = employeeIdOf(body.employeeId),
                leaveType = body.leaveType,
                onDemand = body.onDemand ?: false,
                startDate = body.startDate,
                endDate = body.endDate,
                reason = body.reason
            )
        )
        ResponseEntity.status(HttpStatus.CREATED).body(
            CreateLeaveRequestResponse(
                request = presenter.detail(principal, result.request),
                session = result.session.toResponse()
            )
        )
    }

    @GetMapping("/preview")
    fun preview(
        @RequestParam employeeId: UUID,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) startDate: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) endDate: LocalDate
    ): ResponseEntity<LeaveDaysPreviewResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val preview = queries.previewFor(principal.studioId, employeeId, startDate, endDate)
        return ResponseEntity.ok(
            LeaveDaysPreviewResponse(
                workingDays = preview.workingDays,
                holidays = preview.holidays.map { HolidayResponse(it.date.toString(), it.name) }
            )
        )
    }

    /** Nowy jednorazowy token podpisu osobistego dla szkicu ON_BEHALF wprowadzonego przez wywołującego. */
    @PostMapping("/{id}/employee-signing-session")
    fun employeeSigningSession(@PathVariable id: UUID): ResponseEntity<SigningSessionResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(sessions.forEmployeeInPerson(principal, id).toResponse())
    }

    /**
     * Pracownik podpisuje osobiście na urządzeniu administratora (IN_PERSON). Te same
     * kontrole co samoobsługowe `/submit`: skrót = H1 = bajty z magazynu, token zużywany raz.
     */
    @PostMapping("/{id}/employee-signature")
    fun employeeSignature(
        @PathVariable id: UUID,
        @RequestBody body: SubmitLeaveRequestRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<LeaveRequestDetailResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val submitted = submitHandler.handleInPerson(
            SubmitLeaveRequestCommand(
                studioId = principal.studioId,
                userId = principal.userId,
                userName = principal.fullName,
                requestId = id,
                signatureImageBase64 = body.signatureImageBase64,
                documentSha256 = body.documentSha256,
                challenge = body.challenge,
                declarationAccepted = body.declarationAccepted == true,
                ipAddress = clientIpResolver.resolve(httpRequest),
                userAgent = httpRequest.getHeader(HttpHeaders.USER_AGENT)
            )
        )
        ResponseEntity.ok(presenter.detail(principal, submitted))
    }

    /** Porzucenie szkicu ON_BEHALF przez tego, kto go wprowadził (status WITHDRAWN). */
    @PostMapping("/{id}/discard")
    fun discard(@PathVariable id: UUID): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        withdrawHandler.discardOnBehalf(principal, id)
        ResponseEntity.noContent().build()
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

    /**
     * Dokument do podpisu: wersja podpisana przez pracownika — dokładnie to, co podpisuje
     * rozpatrujący. Wyjątek: szkic ON_BEHALF wprowadzony przez wywołującego — wtedy H1,
     * który pracownik czyta i podpisuje osobiście na jego urządzeniu. Cudzy szkic → 404.
     */
    @GetMapping("/{id}/document")
    fun document(@PathVariable id: UUID): ResponseEntity<ByteArray> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val draft = access.onBehalfDraftOrNull(principal.studioId, id, principal.userId)
        if (draft != null) {
            pdfResponses.forSigning(draft, draft.documentS3Key, draft.documentSha256)
        } else {
            val request = access.submittedRequest(principal.studioId, id)
            pdfResponses.forSigning(request, request.employeeSignedPdfS3Key, request.employeeSignedSha256)
        }
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

    /** Brak pracownika to błąd pola; identyfikator, który nie jest UUID-em, nie wskazuje nikogo — 404. */
    private fun employeeIdOf(raw: String?): UUID {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ValidationException("Wybierz pracownika", field = "employeeId")
        return runCatching { UUID.fromString(value) }.getOrElse { throw NotFoundException(LeaveRequestAccess.EMPLOYEE_NOT_FOUND) }
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

/** Jak [CreateLeaveRequestRequest] plus pracownik; nieznane pola (np. stary zastępca) przepadają. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class OnBehalfLeaveRequestRequest(
    val employeeId: String? = null,
    val leaveType: String? = null,
    val onDemand: Boolean? = false,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val reason: String? = null
)

data class LeaveRequestQueueResponse(
    val items: List<LeaveRequestSummaryResponse>,
    val pendingCount: Int
)

data class LeaveRequestPendingCountResponse(val count: Int)
