package pl.detailing.crm.employee.leaverequest.decide

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditActor
import pl.detailing.crm.audit.domain.AuditEvent
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.employee.leave.domain.LeaveType
import pl.detailing.crm.employee.leave.infrastructure.EmployeeLeaveEntity
import pl.detailing.crm.employee.leave.infrastructure.EmployeeLeaveRepository
import pl.detailing.crm.employee.leaverequest.domain.ApprovalBasis
import pl.detailing.crm.employee.leaverequest.domain.LeaveApprovalPolicy
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestDecidedEvent
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestStatus
import pl.detailing.crm.employee.leaverequest.domain.LeaveSignatureMethod
import pl.detailing.crm.employee.leaverequest.domain.RequestableLeaveTypes
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestEntity
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestRepository
import pl.detailing.crm.employee.leaverequest.pdf.LeaveRequestDocumentService
import pl.detailing.crm.employee.leaverequest.pdf.LeaveRequestPdfRenderer
import pl.detailing.crm.employee.leaverequest.query.LeaveRequestAccess
import pl.detailing.crm.employee.leaverequest.session.LeaveSigningSessions
import pl.detailing.crm.employee.leaverequest.session.SignatureImagePayload
import pl.detailing.crm.role.infrastructure.RoleRepository
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.signing.infrastructure.SignatureImageProcessor
import pl.detailing.crm.user.infrastructure.UserRepository
import pl.detailing.crm.user.signature.UserSignatureService
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

data class DecideLeaveRequestCommand(
    val principal: UserPrincipal,
    val requestId: UUID,
    val approve: Boolean,
    val signatureImageBase64: String?,
    val useSavedSignature: Boolean,
    val documentSha256: String?,
    val challenge: String?,
    val note: String?,
    val ipAddress: String?,
    val userAgent: String?
)

/**
 * Decyzja pracodawcy: PENDING → APPROVED albo REJECTED, zawsze z podpisem rozpatrującego.
 *
 * Odmowa też jest podpisana — to decyzja pracodawcy na dokumencie pracownika, a nie
 * kliknięcie w kolejce. Zatwierdzenie w tej samej transakcji dopisuje urlop do
 * employee_leaves (kalendarz, lista obecności), z odnośnikiem do wniosku.
 *
 * Kolejność sprawdzeń: polityka (samozatwierdzenie, odebrana rola → 403) przed statusem,
 * bo „nie wolno Ci" jest odpowiedzią także na wniosek już rozpatrzony; token i skrót
 * dokumentu przed jakimkolwiek zapisem; na końcu warunkowy UPDATE, który przy dwóch
 * rozpatrujących naraz przepuszcza pierwszego, a drugiemu zwraca 409 i kasuje jego plik.
 */
@Service
class DecideLeaveRequestHandler(
    private val access: LeaveRequestAccess,
    private val policy: LeaveApprovalPolicy,
    private val leaveRequestRepository: LeaveRequestRepository,
    private val employeeRepository: EmployeeRepository,
    private val employeeLeaveRepository: EmployeeLeaveRepository,
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository,
    private val userSignatureService: UserSignatureService,
    private val sessions: LeaveSigningSessions,
    private val documents: LeaveRequestDocumentService,
    private val signatureImageProcessor: SignatureImageProcessor,
    private val auditService: AuditService,
    private val eventPublisher: ApplicationEventPublisher,
    private val transactionTemplate: TransactionTemplate
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern(LeaveRequestPdfRenderer.DATE_PATTERN)

        fun alreadyDecided(request: LeaveRequestEntity) = ConflictException(
            when {
                request.decidedByName != null && request.status in setOf(LeaveRequestStatus.APPROVED, LeaveRequestStatus.REJECTED) ->
                    "Wniosek został już rozpatrzony (${request.decidedByName})"
                request.status == LeaveRequestStatus.WITHDRAWN -> "Pracownik wycofał ten wniosek"
                request.status == LeaveRequestStatus.EXPIRED -> "Wniosek wygasł — termin urlopu już się rozpoczął"
                else -> "Wniosek został już rozpatrzony"
            }
        )
    }

    suspend fun handle(command: DecideLeaveRequestCommand): LeaveRequestEntity = withContext(Dispatchers.IO) {
        val principal = command.principal
        val studio = principal.studioId.value
        val request = access.submittedRequest(principal.studioId, command.requestId)

        val basis = policy.basisFor(principal, request.employeeUserId)
        if (request.status != LeaveRequestStatus.PENDING) throw alreadyDecided(request)

        val note = command.note?.trim()?.takeIf { it.isNotEmpty() }
        if (!command.approve && note == null) {
            throw ValidationException("Podaj uzasadnienie odmowy — pracownik zobaczy je na wniosku", field = "note")
        }
        if (note != null && note.length > LeaveRequestPdfRenderer.MAX_DECISION_NOTE_LENGTH) {
            throw ValidationException(
                "Uzasadnienie decyzji może mieć najwyżej ${LeaveRequestPdfRenderer.MAX_DECISION_NOTE_LENGTH} znaków — tyle mieści się na wniosku",
                field = "note"
            )
        }
        val clientSha256 = command.documentSha256?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ValidationException("Brak skrótu podpisywanego dokumentu", field = "documentSha256")
        val challenge = command.challenge?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ValidationException("Brak tokenu sesji podpisu", field = "challenge")

        // Zapisany podpis to świadomy wybór rozpatrującego (jak zleceniobiorcy na protokole)
        // i trafia na kartę podpisów jako SAVED_SIGNATURE. Brak zapisanego = 400, nie cicha
        // zamiana na „bez podpisu".
        val (rawSignature, method) = if (command.useSavedSignature) {
            val saved = userSignatureService.downloadBytes(principal.studioId, principal.userId)
                ?: throw ValidationException("Nie masz zapisanego podpisu", field = "useSavedSignature")
            saved to LeaveSignatureMethod.SAVED_SIGNATURE
        } else {
            SignatureImagePayload.decode(
                command.signatureImageBase64, "signatureImageBase64",
                if (command.approve) "Podpisz zatwierdzenie, żeby je zapisać" else "Podpisz odmowę, żeby ją zapisać"
            ) to LeaveSignatureMethod.DEVICE_DRAWN
        }

        val decidedAt = Instant.now()
        val decidedByName = principal.fullName.trim().ifBlank { principal.email }
        val roleName = if (basis == ApprovalBasis.PERMISSION) roleNameOf(principal) else null
        val employeeName = employeeRepository.findByIdAndStudioId(request.employeeId, studio)
            ?.let { "${it.firstName} ${it.lastName}".trim() } ?: "—"
        val stamp = LeaveRequestDocumentService.DecisionStamp(
            approved = command.approve,
            decidedByName = decidedByName,
            basis = basis,
            roleName = roleName,
            note = note,
            method = method,
            decidedAt = decidedAt,
            ipAddress = command.ipAddress?.take(45),
            userAgent = command.userAgent?.take(500)
        )

        var normalized: ByteArray? = null
        val stored = try {
            sessions.consumeDecision(request.id, principal.userId, challenge)
            val employeeSignedKey = request.employeeSignedPdfS3Key
                ?: throw ConflictException("Wniosek nie ma wersji podpisanej przez pracownika")
            val employeeSignedBytes = documents.download(employeeSignedKey)
            sessions.verifyDocument(clientSha256, request.employeeSignedSha256 ?: "", employeeSignedBytes)
            normalized = signatureImageProcessor.normalizeToTransparentPng(rawSignature)
            documents.storeFinal(request, employeeName, employeeSignedBytes, normalized, stamp)
        } finally {
            signatureImageProcessor.wipe(rawSignature)
            signatureImageProcessor.wipe(normalized)
        }

        try {
            transactionTemplate.executeWithoutResult {
                var employeeLeaveId: UUID? = null
                if (command.approve) {
                    employeeLeaveId = createLeave(request, principal.userId)
                }
                val updated = leaveRequestRepository.markDecided(
                    id = request.id,
                    studioId = studio,
                    status = if (command.approve) LeaveRequestStatus.APPROVED else LeaveRequestStatus.REJECTED,
                    decidedBy = principal.userId.value,
                    decidedByName = decidedByName,
                    basis = basis,
                    roleName = roleName,
                    decidedAt = decidedAt,
                    note = note,
                    method = method,
                    inputSha256 = clientSha256.lowercase(),
                    ip = stamp.ipAddress,
                    userAgent = stamp.userAgent,
                    finalKey = stored.s3Key,
                    finalSha256 = stored.sha256,
                    employeeLeaveId = employeeLeaveId
                )
                if (updated == 0) {
                    // Wyjątek wycofuje też wpis urlopu dodany wyżej.
                    throw alreadyDecided(leaveRequestRepository.findByIdAndStudioId(request.id, studio) ?: request)
                }
                request.employeeUserId?.let { employeeUserId ->
                    eventPublisher.publishEvent(
                        LeaveRequestDecidedEvent(
                            studioId = principal.studioId,
                            requestId = request.id,
                            employeeUserId = UserId(employeeUserId),
                            outcome = if (command.approve) LeaveRequestStatus.APPROVED else LeaveRequestStatus.REJECTED,
                            kindLabel = RequestableLeaveTypes.label(request.leaveType, request.onDemand),
                            startDate = request.startDate,
                            endDate = request.endDate,
                            decidedByName = decidedByName
                        )
                    )
                }
            }
        } catch (e: Exception) {
            documents.deleteQuietly(stored.s3Key)
            throw e
        }

        val decided = access.submittedRequest(principal.studioId, request.id)
        auditService.recordSync(
            AuditEvent(
                studioId = principal.studioId,
                actor = AuditActor.employee(principal.userId, decidedByName),
                module = AuditModule.EMPLOYEE,
                action = if (command.approve) AuditAction.LEAVE_APPROVED else AuditAction.LEAVE_REJECTED,
                entityId = request.employeeId.toString(),
                entityDisplayName = employeeName,
                changes = listOfNotNull(
                    FieldChange("status", LeaveRequestStatus.PENDING.name, decided.status.name),
                    note?.let { FieldChange("decisionNote", null, it) }
                ),
                metadata = mapOfNotNull(
                    "leaveRequestId" to request.id.toString(),
                    "number" to request.number,
                    "basis" to basis.name,
                    "roleName" to roleName,
                    "signatureMethod" to method.name,
                    "documentSha256" to stored.sha256
                )
            )
        )
        logger.info(
            "Leave request decided: studioId={}, requestId={}, status={}, basis={}",
            principal.studioId, request.id, decided.status, basis
        )
        decided
    }

    /**
     * Urlop jako fakt: wpis w employee_leaves pod blokadą pracownika, po sprawdzeniu, że
     * w międzyczasie nikt nie wpisał tej osobie innej nieobecności (np. L4 z e-ZLA).
     * Urlop na żądanie to w kalendarzu zwykły wypoczynkowy — konsumenci nie znają flagi.
     */
    private fun createLeave(request: LeaveRequestEntity, approverId: UserId): UUID {
        employeeRepository.lockForUpdate(request.employeeId, request.studioId)
            ?: throw ConflictException("Pracownik został usunięty — wniosku nie można zatwierdzić")
        employeeLeaveRepository.findOverlappingOfEmployee(request.studioId, request.employeeId, request.startDate, request.endDate)
            .firstOrNull()?.let { other ->
                val what = if (other.leaveType == LeaveType.SICK) "zwolnienie lekarskie" else "nieobecność"
                throw ConflictException(
                    "W tym terminie pracownik ma już wpisane $what (${other.startDate.format(DATE)}–${other.endDate.format(DATE)}). " +
                        "Odrzuć wniosek albo usuń tamten wpis."
                )
            }
        val leave = EmployeeLeaveEntity(
            id = UUID.randomUUID(),
            studioId = request.studioId,
            employeeId = request.employeeId,
            leaveType = request.leaveType,
            startDate = request.startDate,
            endDate = request.endDate,
            note = "Wniosek ${request.number}" + if (request.onDemand) " (na żądanie)" else "",
            createdBy = approverId.value,
            createdAt = Instant.now(),
            leaveRequestId = request.id
        )
        employeeLeaveRepository.save(leave)
        return leave.id
    }

    /** Nazwa roli rozpatrującego z chwili decyzji — drukowana w „Podstawie uprawnienia". */
    private fun roleNameOf(principal: UserPrincipal): String? {
        val roleId = userRepository.findByIdAndStudioId(principal.userId.value, principal.studioId.value)?.customRoleId
            ?: return null
        return roleRepository.findByIdAndStudioId(roleId, principal.studioId.value)?.name
    }

    private fun mapOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> =
        pairs.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
}
