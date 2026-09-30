package pl.detailing.crm.employee.leaverequest.withdraw

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditActor
import pl.detailing.crm.audit.domain.AuditEvent
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestStatus
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestEntity
import pl.detailing.crm.employee.leaverequest.infrastructure.LeaveRequestRepository
import pl.detailing.crm.employee.leaverequest.query.LeaveRequestAccess
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant
import java.util.UUID

/**
 * Pracownik wycofuje własny wniosek — ze szkicu albo z oczekującego, zawsze przed decyzją.
 * Po decyzji wniosek należy już do pracodawcy: zatwierdzony urlop odwołuje rozpatrujący
 * (CancelLeaveRequestHandler), z powodem. Pliki zostają w historii bez zmian.
 */
@Service
class WithdrawLeaveRequestHandler(
    private val access: LeaveRequestAccess,
    private val leaveRequestRepository: LeaveRequestRepository,
    private val auditService: AuditService,
    private val transactionTemplate: TransactionTemplate
) {
    suspend fun handle(studioId: StudioId, userId: UserId, userName: String, requestId: UUID): LeaveRequestEntity =
        withContext(Dispatchers.IO) {
            val employee = access.employeeOf(studioId, userId)
            val request = access.ownRequest(studioId, employee.id, requestId)
            if (request.status !in setOf(LeaveRequestStatus.DRAFT, LeaveRequestStatus.PENDING)) throw notWithdrawable()

            transactionTemplate.executeWithoutResult {
                val updated = leaveRequestRepository.markWithdrawn(
                    id = request.id, studioId = studioId.value, employeeId = employee.id,
                    at = Instant.now(), by = userId.value
                )
                if (updated == 0) throw notWithdrawable()
            }

            auditService.recordSync(
                AuditEvent(
                    studioId = studioId,
                    actor = AuditActor.employee(userId, userName),
                    module = AuditModule.EMPLOYEE,
                    action = AuditAction.LEAVE_CANCELLED,
                    entityId = employee.id.toString(),
                    entityDisplayName = "${employee.firstName} ${employee.lastName}".trim(),
                    changes = listOf(FieldChange("status", request.status.name, LeaveRequestStatus.WITHDRAWN.name)),
                    metadata = mapOf("leaveRequestId" to request.id.toString(), "number" to request.number)
                )
            )
            access.ownRequest(studioId, employee.id, request.id)
        }

    private fun notWithdrawable() =
        ConflictException("Wniosek został już rozpatrzony — wycofać można tylko wniosek przed decyzją")
}
