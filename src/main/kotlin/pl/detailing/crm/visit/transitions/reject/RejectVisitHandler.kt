package pl.detailing.crm.visit.transitions.reject

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository

@Service
class RejectVisitHandler(
    private val visitRepository: VisitRepository,
    private val auditService: AuditService
    // TODO: Add services for side-effects (EmailService, SMSService, etc.)
) {

    @Transactional
    suspend fun handle(command: RejectVisitCommand): RejectVisitResult {
        // Step 1: Load visit
        val visitEntity = visitRepository.findByIdAndStudioId(command.visitId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Visit with ID '${command.visitId}' not found")

        // Force load lazy collections within transaction
        visitEntity.serviceItems.size  // Force load serviceItems
        visitEntity.photos.size  // Force load photos

        val visit = visitEntity.toDomain()

        // Step 1a: Nic do zrobienia — patrz [RejectVisitResult.alreadyInTargetState].
        // Powód odrzucenia z pierwszego żądania zostaje; drugie nie dopisuje go po raz drugi.
        if (visit.status == VisitStatus.REJECTED) {
            return RejectVisitResult(
                visitId = visit.id,
                newStatus = visit.status,
                alreadyInTargetState = true
            )
        }

        // Step 2: Perform state transition (domain logic with validation)
        val updatedVisit = visit.reject(command.userId)

        // Step 3: Persist changes
        val updatedEntity = VisitEntity.fromDomain(updatedVisit)
        visitRepository.save(updatedEntity)

        // Step 4: Store rejection reason in technical notes if provided
        if (command.rejectionReason != null) {
            val technicalNotes = if (updatedVisit.technicalNotes.isNullOrBlank()) {
                "REJECTED: ${command.rejectionReason}"
            } else {
                "${updatedVisit.technicalNotes}\n\nREJECTED: ${command.rejectionReason}"
            }
            updatedEntity.technicalNotes = technicalNotes
            visitRepository.save(updatedEntity)
        }

        // Step 5: Audit logging
        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName ?: "",
            module = AuditModule.VISIT,
            entityId = command.visitId.value.toString(),
            entityDisplayName = "Wizyta #${visit.visitNumber}",
            action = AuditAction.VISIT_REJECTED,
            changes = listOf(FieldChange("status", visit.status.name, updatedVisit.status.name)),
            metadata = command.rejectionReason?.let { mapOf("rejectionReason" to it) } ?: emptyMap()
        ))

        // Step 6: Side-effects (to be implemented)
        // TODO: Send notification to customer about rejection
        // TODO: Cancel any pending operations
        // Example:
        // notificationService.notifyCustomerVisitRejected(
        //     customerId = visit.customerId,
        //     visitNumber = visit.visitNumber,
        //     reason = command.rejectionReason
        // )

        return RejectVisitResult(
            visitId = updatedVisit.id,
            newStatus = updatedVisit.status
        )
    }
}

/**
 * Command to reject visit
 */
data class RejectVisitCommand(
    val studioId: StudioId,
    val userId: UserId,
    val visitId: VisitId,
    val rejectionReason: String?,
    val userName: String? = null
)

/**
 * Result of rejecting visit
 */
data class RejectVisitResult(
    val visitId: VisitId,
    val newStatus: VisitStatus,
    /**
     * Powtórzone żądanie na wizycie, która JUŻ jest w docelowym stanie, nie jest błędem:
     * cel wywołującego został osiągnięty. Do tej pory kończyło się 409 z komunikatem
     * „Cannot transition from READY_FOR_PICKUP to READY_FOR_PICKUP" — pracownik widział
     * czerwony błąd za to, że ktoś inny (albo on sam sekundę wcześniej, albo drugie
     * kliknięcie) zdążył pierwszy. Zwracamy stan bieżący z flagą [alreadyInTargetState],
     * bez ponownego audytu i BEZ efektów ubocznych: klient nie dostaje drugiego SMS-a,
     * a księgowość drugiego dokumentu.
     */
    val alreadyInTargetState: Boolean = false
)
