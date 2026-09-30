package pl.detailing.crm.employee.leaverequest

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
import pl.detailing.crm.employee.leaverequest.create.CreateLeaveRequestCommand
import pl.detailing.crm.employee.leaverequest.create.CreateLeaveRequestHandler
import pl.detailing.crm.employee.leaverequest.query.LeaveRequestAccess
import pl.detailing.crm.employee.leaverequest.query.LeaveRequestQueryService
import pl.detailing.crm.employee.leaverequest.session.LeaveSigningSessionService
import pl.detailing.crm.employee.leaverequest.session.SigningSession
import pl.detailing.crm.employee.leaverequest.submit.SubmitLeaveRequestCommand
import pl.detailing.crm.employee.leaverequest.submit.SubmitLeaveRequestHandler
import pl.detailing.crm.employee.leaverequest.withdraw.WithdrawLeaveRequestHandler
import pl.detailing.crm.security.ClientIpResolver
import java.time.LocalDate
import java.util.UUID

/**
 * Samoobsługa pracownika: moje wnioski urlopowe (`docs/api-leave-requests.md`).
 *
 * Bez uprawnienia — każdy, kogo konto jest powiązane z rekordem pracownika, składa
 * i wycofuje WYŁĄCZNIE własne wnioski. W ścieżce nie ma employeeId: pracownik to zawsze
 * rekord zalogowanego konta ([LeaveRequestAccess.employeeOf]), a cudzy identyfikator
 * wniosku daje 404. Allowlista w AuthorizationSurfaceScanTest, jak MyWorkTimeController.
 */
@RestController
@RequestMapping("/api/v1/my/leave-requests")
class MyLeaveRequestController(
    private val queries: LeaveRequestQueryService,
    private val access: LeaveRequestAccess,
    private val presenter: LeaveRequestPresenter,
    private val pdfResponses: LeaveRequestPdfResponses,
    private val sessions: LeaveSigningSessionService,
    private val createHandler: CreateLeaveRequestHandler,
    private val submitHandler: SubmitLeaveRequestHandler,
    private val withdrawHandler: WithdrawLeaveRequestHandler,
    @Value("\${security.rate-limit.trusted-proxies:}") trustedProxies: String
) {
    // Ten sam model zaufania do nagłówków pośrednika co limity żądań: adres IP trafia
    // na kartę podpisów, więc nie może pochodzić z nagłówka, który klient wpisał sam.
    private val clientIpResolver = ClientIpResolver(trustedProxies.split(","))

    @GetMapping
    fun list(): ResponseEntity<MyLeaveRequestsResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val mine = queries.myRequests(principal.studioId, principal.userId)
        return ResponseEntity.ok(
            MyLeaveRequestsResponse(
                requests = presenter.summaries(principal.studioId.value, mine.requests),
                summary = MyLeaveSummaryResponse(
                    year = mine.year,
                    usedWorkingDays = mine.usedWorkingDays,
                    pendingCount = mine.pendingCount
                )
            )
        )
    }

    @GetMapping("/preview")
    fun preview(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) startDate: LocalDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) endDate: LocalDate
    ): ResponseEntity<LeaveDaysPreviewResponse> {
        SecurityContextHelper.getCurrentUser()
        val preview = queries.preview(startDate, endDate)
        return ResponseEntity.ok(
            LeaveDaysPreviewResponse(
                workingDays = preview.workingDays,
                holidays = preview.holidays.map { HolidayResponse(it.date.toString(), it.name) }
            )
        )
    }

    @PostMapping
    fun create(@RequestBody body: CreateLeaveRequestRequest): ResponseEntity<CreateLeaveRequestResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val result = createHandler.handle(
            CreateLeaveRequestCommand(
                studioId = principal.studioId,
                userId = principal.userId,
                userName = principal.fullName,
                leaveType = body.leaveType,
                onDemand = body.onDemand ?: false,
                startDate = body.startDate,
                endDate = body.endDate,
                reason = body.reason,
                substituteEmployeeId = body.substituteEmployeeId
            )
        )
        ResponseEntity.status(HttpStatus.CREATED).body(
            CreateLeaveRequestResponse(
                request = presenter.detail(principal, result.request),
                session = result.session.toResponse()
            )
        )
    }

    /** Nowy jednorazowy token dla szkicu — po wygaśnięciu albo po nieudanej próbie podpisu. */
    @PostMapping("/{id}/signing-session")
    fun signingSession(@PathVariable id: UUID): ResponseEntity<SigningSessionResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(sessions.forEmployee(principal, id).toResponse())
    }

    /** Dokładnie te bajty, których skrót jest w sesji podpisu (H1). */
    @GetMapping("/{id}/document")
    fun document(@PathVariable id: UUID): ResponseEntity<ByteArray> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val employee = access.employeeOf(principal.studioId, principal.userId)
        val request = access.ownRequest(principal.studioId, employee.id, id)
        pdfResponses.forSigning(request, request.documentS3Key, request.documentSha256)
    }

    @PostMapping("/{id}/submit")
    fun submit(
        @PathVariable id: UUID,
        @RequestBody body: SubmitLeaveRequestRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<LeaveRequestDetailResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val submitted = submitHandler.handle(
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

    @PostMapping("/{id}/withdraw")
    fun withdraw(@PathVariable id: UUID): ResponseEntity<LeaveRequestDetailResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val withdrawn = withdrawHandler.handle(principal.studioId, principal.userId, principal.fullName, id)
        ResponseEntity.ok(presenter.detail(principal, withdrawn))
    }

    /** Aktualny PDF: final, a przed decyzją wersja podpisana przez pracownika. */
    @GetMapping("/{id}/file")
    fun file(@PathVariable id: UUID): ResponseEntity<ByteArray> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val employee = access.employeeOf(principal.studioId, principal.userId)
        pdfResponses.current(access.ownRequest(principal.studioId, employee.id, id))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Request / Response DTOs — nazwy pól 1:1 z docs/api-leave-requests.md.
//
// Bez @Pii na nazwiskach: to dane pracowników, nie klientów studia (pakiet
// pl.detailing.crm.employee jest świadomie poza PiiResponseSurfaceScanTest, a kalendarz
// urlopów zwraca te same nazwiska każdemu zalogowanemu). @Pii maskuje wartość każdemu bez
// CUSTOMERS_VIEW — pracownik bez dostępu do klientów zobaczyłby gwiazdki zamiast
// WŁASNEGO nazwiska i nazwiska osoby, która rozpatrzyła jego wniosek.
// ─────────────────────────────────────────────────────────────────────────────

data class CreateLeaveRequestRequest(
    val leaveType: String? = null,
    val onDemand: Boolean? = false,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null,
    val reason: String? = null,
    val substituteEmployeeId: String? = null
)

data class SubmitLeaveRequestRequest(
    val signatureImageBase64: String? = null,
    val documentSha256: String? = null,
    val challenge: String? = null,
    val declarationAccepted: Boolean? = null
)

data class SigningSessionResponse(val documentSha256: String, val challenge: String)

fun SigningSession.toResponse() = SigningSessionResponse(documentSha256 = documentSha256, challenge = challenge)

data class CreateLeaveRequestResponse(
    val request: LeaveRequestDetailResponse,
    val session: SigningSessionResponse
)

data class MyLeaveRequestsResponse(
    val requests: List<LeaveRequestSummaryResponse>,
    val summary: MyLeaveSummaryResponse
)

data class MyLeaveSummaryResponse(val year: Int, val usedWorkingDays: Int, val pendingCount: Int)

data class LeaveDaysPreviewResponse(val workingDays: Int, val holidays: List<HolidayResponse>)

data class HolidayResponse(val date: String, val name: String)

data class LeaveRequestSummaryResponse(
    val id: String,
    val number: String,
    val employeeId: String,
    val employeeName: String,
    val leaveType: String,
    val onDemand: Boolean,
    val startDate: String,
    val endDate: String,
    val workingDays: Int,
    val status: String,
    val reason: String?,
    val substituteEmployeeId: String?,
    val substituteName: String?,
    val createdAt: String,
    val employeeSignedAt: String?,
    val decidedAt: String?,
    val decidedByName: String?,
    val decisionNote: String?,
    val cancelReason: String?
)

enum class OverlappingAbsenceKind { LEAVE, PENDING_REQUEST }

data class OverlappingAbsenceResponse(
    val employeeId: String,
    val employeeName: String,
    val startDate: String,
    val endDate: String,
    val kind: OverlappingAbsenceKind
)

data class LeaveRequestDetailResponse(
    val id: String,
    val number: String,
    val employeeId: String,
    val employeeName: String,
    val leaveType: String,
    val onDemand: Boolean,
    val startDate: String,
    val endDate: String,
    val workingDays: Int,
    val status: String,
    val reason: String?,
    val substituteEmployeeId: String?,
    val substituteName: String?,
    val createdAt: String,
    val employeeSignedAt: String?,
    val decidedAt: String?,
    val decidedByName: String?,
    val decisionNote: String?,
    val cancelReason: String?,
    val employeeSignatureMethod: String?,
    val decisionSignatureMethod: String?,
    val decidedByBasis: String?,
    val decidedByRoleName: String?,
    val overlappingAbsences: List<OverlappingAbsenceResponse>,
    val canDecide: Boolean,
    val decisionBlockedReason: String?,
    val canCancel: Boolean
)
