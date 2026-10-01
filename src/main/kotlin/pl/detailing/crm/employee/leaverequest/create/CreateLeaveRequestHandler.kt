package pl.detailing.crm.employee.leaverequest.create

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestOrigin
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestStatus
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestValidator
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestCounterRepository
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestEntity
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestRepository
import pl.detailing.crm.employee.leaverequest.pdf.LeaveRequestDocumentService
import pl.detailing.crm.employee.leaverequest.query.LeaveRequestAccess
import pl.detailing.crm.employee.leaverequest.session.LeaveSigningSessions
import pl.detailing.crm.employee.leaverequest.session.SigningSession
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class CreateLeaveRequestCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userName: String,
    val leaveType: String?,
    val onDemand: Boolean,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val reason: String?
)

data class CreateLeaveRequestResult(val request: LeaveRequestEntity, val session: SigningSession)

/**
 * Pracownik zaczyna wniosek: walidacja, numer, wypełniony PDF (H1) i sesja podpisu.
 *
 * Wynikiem jest SZKIC — dokument, który pracownik zobaczy dokładnie w tej postaci przed
 * podpisem. Szkic nie trafia do kolejki ani do kalendarza, a job usuwa go po dobie.
 *
 * Numer nadajemy tutaj, a nie przy złożeniu, bo jest wydrukowany na dokumencie: nadany
 * później zmieniłby bajty PDF, a więc skrót, który pracownik podpisuje. Ceną są luki
 * w numeracji po porzuconych szkicach — numer wniosku to identyfikator dokumentu, a nie
 * licznik złożonych wniosków, więc luka niczego nie fałszuje.
 */
@Service
class CreateLeaveRequestHandler(
    private val employeeRepository: EmployeeRepository,
    private val leaveRequestRepository: LeaveRequestRepository,
    private val counterRepository: LeaveRequestCounterRepository,
    private val validator: LeaveRequestValidator,
    private val access: LeaveRequestAccess,
    private val documents: LeaveRequestDocumentService,
    private val sessions: LeaveSigningSessions,
    private val transactionTemplate: TransactionTemplate
) {
    private val warsaw = ZoneId.of("Europe/Warsaw")

    private data class Prepared(
        val employeeId: UUID,
        val employeeName: String,
        val employeeEmail: String?,
        val employeePhone: String?,
        val workingDays: Int,
        val number: String
    )

    suspend fun handle(command: CreateLeaveRequestCommand): CreateLeaveRequestResult = withContext(Dispatchers.IO) {
        val draft = validator.parse(command.leaveType, command.onDemand, command.startDate, command.endDate, command.reason)
        val today = LocalDate.now(warsaw)
        val requestId = UUID.randomUUID()
        val studio = command.studioId.value

        // Jedna prawdziwa transakcja (TransactionTemplate): ciało `@Transactional suspend`
        // na Dispatchers.IO wymyka się transakcji z interceptora (patrz AuditLogWriter),
        // a blokada wiersza pracownika i licznik numeracji jej wymagają.
        val prepared = transactionTemplate.execute {
            val employee = access.employeeOf(command.studioId, command.userId)
            employeeRepository.lockForUpdate(employee.id, studio)
            val workingDays = validator.checkTerm(draft, today)
            validator.checkAgainstExisting(studio, employee.id, requestId, draft)
            val year = today.year
            Prepared(
                employeeId = employee.id,
                employeeName = "${employee.firstName} ${employee.lastName}".trim(),
                employeeEmail = employee.email,
                employeePhone = employee.phone,
                workingDays = workingDays,
                number = "WU/$year/${counterRepository.nextValue(studio, year).toString().padStart(4, '0')}"
            )
        }!!

        // PDF i S3 poza transakcją bazy: plik nie trzyma blokady pracownika. Gdy zapis
        // wiersza się nie uda, zostaje osierocony plik i luka w numeracji — nic, co
        // udawałoby istniejący wniosek.
        val stored = documents.createDraft(
            command.studioId, requestId,
            LeaveRequestDocumentService.DraftContent(
                number = prepared.number,
                employeeName = prepared.employeeName,
                employeeEmail = prepared.employeeEmail,
                employeePhone = prepared.employeePhone,
                startDate = draft.startDate,
                endDate = draft.endDate,
                workingDays = prepared.workingDays,
                leaveType = draft.leaveType,
                onDemand = draft.onDemand,
                reason = draft.reason
            )
        )

        val now = Instant.now()
        val entity = try {
            leaveRequestRepository.save(
                LeaveRequestEntity(
                    id = requestId,
                    studioId = studio,
                    number = prepared.number,
                    employeeId = prepared.employeeId,
                    employeeUserId = command.userId.value,
                    leaveType = draft.leaveType,
                    onDemand = draft.onDemand,
                    startDate = draft.startDate,
                    endDate = draft.endDate,
                    workingDays = prepared.workingDays,
                    reason = draft.reason,
                    status = LeaveRequestStatus.DRAFT,
                    origin = LeaveRequestOrigin.SELF_SERVICE,
                    createdBy = command.userId.value,
                    createdByName = command.userName.trim().ifBlank { null },
                    createdAt = now,
                    documentS3Key = stored.s3Key,
                    documentSha256 = stored.sha256,
                    updatedAt = now
                )
            )
        } catch (e: Exception) {
            documents.deleteQuietly(stored.s3Key)
            throw e
        }

        CreateLeaveRequestResult(entity, sessions.issueEmployee(requestId, stored.sha256))
    }
}
