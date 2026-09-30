package pl.detailing.crm.employee.leaverequest.cancel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import pl.detailing.crm.employee.leave.infrastructure.EmployeeLeaveRepository
import pl.detailing.crm.employee.leaverequest.domain.LeaveApprovalPolicy
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestDecidedEvent
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestStatus
import pl.detailing.crm.employee.leaverequest.domain.RequestableLeaveTypes
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestEntity
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestRepository
import pl.detailing.crm.employee.leaverequest.query.LeaveRequestAccess
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class CancelLeaveRequestCommand(
    val principal: UserPrincipal,
    val requestId: UUID,
    val reason: String?
)

/**
 * Odwołanie zatwierdzonego urlopu (APPROVED → CANCELLED) przed jego rozpoczęciem.
 *
 * Odwołać może ten, kto może rozpatrywać — ta sama [LeaveApprovalPolicy], także zakaz
 * własnego wniosku. Powód jest wymagany, bo pracownik traci zaplanowany urlop i musi
 * wiedzieć dlaczego. Wpis w employee_leaves znika w tej samej transakcji: kalendarz
 * i lista obecności nie mogą pokazywać urlopu, którego już nie ma.
 *
 * Faza 2 projektu: podpisany wniosek o odwołanie. Dziś odwołanie jest wpisem w historii
 * i dzienniku zdarzeń, a podpisany dokument H3 zostaje bez zmian.
 */
@Service
class CancelLeaveRequestHandler(
    private val access: LeaveRequestAccess,
    private val policy: LeaveApprovalPolicy,
    private val leaveRequestRepository: LeaveRequestRepository,
    private val employeeLeaveRepository: EmployeeLeaveRepository,
    private val auditService: AuditService,
    private val eventPublisher: ApplicationEventPublisher,
    private val transactionTemplate: TransactionTemplate
) {
    private val warsaw = ZoneId.of("Europe/Warsaw")

    companion object {
        const val MAX_REASON_LENGTH = 1000
    }

    suspend fun handle(command: CancelLeaveRequestCommand): LeaveRequestEntity = withContext(Dispatchers.IO) {
        val principal = command.principal
        val studio = principal.studioId.value
        val request = access.submittedRequest(principal.studioId, command.requestId)
        policy.basisFor(principal, request.employeeUserId)

        val reason = command.reason?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ValidationException("Podaj powód odwołania urlopu — pracownik go zobaczy", field = "reason")
        if (reason.length > MAX_REASON_LENGTH) {
            throw ValidationException("Powód odwołania może mieć najwyżej $MAX_REASON_LENGTH znaków", field = "reason")
        }
        if (request.status != LeaveRequestStatus.APPROVED) {
            throw ConflictException("Odwołać można tylko zatwierdzony wniosek")
        }
        if (!LocalDate.now(warsaw).isBefore(request.startDate)) {
            throw ValidationException("Urlopu, który już się rozpoczął, nie można odwołać")
        }

        val decidedByName = principal.fullName.trim().ifBlank { principal.email }
        transactionTemplate.executeWithoutResult {
            val updated = leaveRequestRepository.markCancelled(
                id = request.id, studioId = studio, at = Instant.now(), by = principal.userId.value, reason = reason
            )
            if (updated == 0) throw ConflictException("Wniosek zmienił się w międzyczasie — odśwież widok")
            employeeLeaveRepository.deleteByLeaveRequestId(studio, request.id)
            request.employeeUserId?.let { employeeUserId ->
                eventPublisher.publishEvent(
                    LeaveRequestDecidedEvent(
                        studioId = principal.studioId,
                        requestId = request.id,
                        employeeUserId = UserId(employeeUserId),
                        outcome = LeaveRequestStatus.CANCELLED,
                        kindLabel = RequestableLeaveTypes.label(request.leaveType, request.onDemand),
                        startDate = request.startDate,
                        endDate = request.endDate,
                        decidedByName = decidedByName
                    )
                )
            }
        }

        auditService.recordSync(
            AuditEvent(
                studioId = principal.studioId,
                actor = AuditActor.employee(principal.userId, decidedByName),
                module = AuditModule.EMPLOYEE,
                action = AuditAction.LEAVE_CANCELLED,
                entityId = request.employeeId.toString(),
                changes = listOf(
                    FieldChange("status", LeaveRequestStatus.APPROVED.name, LeaveRequestStatus.CANCELLED.name),
                    FieldChange("cancelReason", null, reason)
                ),
                metadata = mapOf("leaveRequestId" to request.id.toString(), "number" to request.number)
            )
        )
        access.submittedRequest(principal.studioId, request.id)
    }
}
