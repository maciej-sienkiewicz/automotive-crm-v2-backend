package pl.detailing.crm.visit.transitions.confirm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.protocol.infrastructure.VisitProtocolRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant

/**
 * Handler for confirming a DRAFT visit and making it active.
 *
 * This operation:
 * - Validates that all mandatory protocols are signed
 * - Changes visit status from DRAFT to IN_PROGRESS
 * - Updates appointment status from CONFIRMED to CONVERTED
 * - Makes the visit immutable (cannot be cancelled anymore)
 */
@Service
class ConfirmVisitHandler(
    private val visitRepository: VisitRepository,
    private val visitProtocolRepository: VisitProtocolRepository,
    private val appointmentRepository: AppointmentRepository
) {
    @Transactional
    suspend fun handle(command: ConfirmVisitCommand): ConfirmVisitResult =
        withContext(Dispatchers.IO) {
            // Load visit
            val visitEntity = visitRepository.findByIdAndStudioId(
                command.visitId.value,
                command.studioId.value
            ) ?: throw EntityNotFoundException("Visit not found")

            // Validate visit is in DRAFT status (check directly on entity to avoid lazy loading issues)
            if (visitEntity.status != VisitStatus.DRAFT) {
                throw ValidationException("Only DRAFT visits can be confirmed. Current status: ${visitEntity.status}")
            }

            // Check if all mandatory protocols are signed
            val protocols = visitProtocolRepository.findAllByVisitIdAndStudioIdAndStage(
                command.visitId.value,
                command.studioId.value,
                ProtocolStage.CHECK_IN
            )

            val mandatoryProtocols = protocols.filter { it.isMandatory }
            val unsignedMandatory = mandatoryProtocols.filter { it.status != VisitProtocolStatus.SIGNED }

            if (unsignedMandatory.isNotEmpty()) {
                throw ValidationException(
                    "Cannot confirm visit: ${unsignedMandatory.size} mandatory protocol(s) not signed yet. " +
                    "All mandatory documents must be signed before confirming the visit."
                )
            }

            // Update visit status to IN_PROGRESS
            visitEntity.status = VisitStatus.IN_PROGRESS
            visitEntity.updatedBy = command.userId.value
            visitEntity.updatedAt = Instant.now()
            visitRepository.save(visitEntity)

            // Update appointment status to CONVERTED
            if (visitEntity.appointmentId != null) {
                val appointmentEntity = appointmentRepository.findByIdAndStudioId(
                    visitEntity.appointmentId!!,
                    command.studioId.value
                )
                if (appointmentEntity != null) {
                    appointmentEntity.status = AppointmentStatus.CONVERTED
                    appointmentEntity.updatedBy = command.userId.value
                    appointmentEntity.updatedAt = Instant.now()
                    appointmentRepository.save(appointmentEntity)
                }
            }

            ConfirmVisitResult(visitId = command.visitId)
        }
}

data class ConfirmVisitCommand(
    val visitId: VisitId,
    val studioId: StudioId,
    val userId: UserId
)

data class ConfirmVisitResult(
    val visitId: VisitId
)
