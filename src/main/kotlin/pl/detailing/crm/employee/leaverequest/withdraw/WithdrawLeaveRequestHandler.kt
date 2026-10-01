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
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.employee.infrastructure.EmployeeRepository
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
    private val employeeRepository: EmployeeRepository,
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

    /**
     * Administrator porzuca szkic, który wprowadził w imieniu pracownika (pracownik nie
     * podpisał albo wniosek był pomyłką). Tylko szkic i tylko własny: po podpisie
     * pracownika to już wniosek do rozpatrzenia — odrzuca się go decyzją z uzasadnieniem.
     * Status WITHDRAWN jak przy wycofaniu, pliki zostają.
     */
    suspend fun discardOnBehalf(principal: UserPrincipal, requestId: UUID) = withContext(Dispatchers.IO) {
        val request = access.onBehalfRequest(principal.studioId, requestId, principal.userId)
        if (request.status != LeaveRequestStatus.DRAFT) throw notDiscardable(request.status)

        transactionTemplate.executeWithoutResult {
            val updated = leaveRequestRepository.markOnBehalfDraftDiscarded(
                id = request.id, studioId = principal.studioId.value, at = Instant.now(), by = principal.userId.value
            )
            if (updated == 0) {
                throw notDiscardable(leaveRequestRepository.findByIdAndStudioId(request.id, principal.studioId.value)?.status)
            }
        }

        val employeeName = employeeRepository.findByIdAndStudioId(request.employeeId, principal.studioId.value)
            ?.let { "${it.firstName} ${it.lastName}".trim() } ?: "—"
        auditService.recordSync(
            AuditEvent(
                studioId = principal.studioId,
                actor = AuditActor.employee(principal.userId, principal.fullName),
                module = AuditModule.EMPLOYEE,
                action = AuditAction.LEAVE_CANCELLED,
                entityId = request.employeeId.toString(),
                entityDisplayName = employeeName,
                changes = listOf(FieldChange("status", LeaveRequestStatus.DRAFT.name, LeaveRequestStatus.WITHDRAWN.name)),
                metadata = mapOf(
                    "leaveRequestId" to request.id.toString(),
                    "number" to request.number,
                    "origin" to request.origin.name
                )
            )
        )
    }

    private fun notDiscardable(status: LeaveRequestStatus?) = ConflictException(
        if (status == LeaveRequestStatus.WITHDRAWN) "Ten szkic został już porzucony"
        else "Pracownik podpisał już ten wniosek — porzucić można tylko szkic. Rozpatrz go albo odrzuć."
    )

    private fun notWithdrawable() =
        ConflictException("Wniosek został już rozpatrzony — wycofać można tylko wniosek przed decyzją")
}
