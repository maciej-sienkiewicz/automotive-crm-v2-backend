package pl.detailing.crm.appointment.recurrence.delete

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.appointment.recurrence.infrastructure.RecurrenceSeriesRepository
import pl.detailing.crm.appointment.recurrence.update.RecurrenceEditScope
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.shared.*
import java.time.Instant

data class DeleteRecurringAppointmentCommand(
    val appointmentId: AppointmentId,
    val studioId: StudioId,
    val userId: UserId,
    val userName: String? = null,
    val scope: RecurrenceEditScope
)

data class DeleteRecurringAppointmentResult(
    val deletedCount: Int,
    val skippedConvertedCount: Int
)

@Service
class DeleteRecurringAppointmentHandler(
    private val appointmentRepository: AppointmentRepository,
    private val recurrenceSeriesRepository: RecurrenceSeriesRepository,
    private val auditService: AuditService
) {

    @Transactional
    suspend fun handle(command: DeleteRecurringAppointmentCommand): DeleteRecurringAppointmentResult =
        withContext(Dispatchers.IO) {
            val anchor = appointmentRepository.findByIdAndStudioId(
                command.appointmentId.value,
                command.studioId.value
            ) ?: throw EntityNotFoundException("Rezerwacja nie została znaleziona: ${command.appointmentId}")

            val seriesId = anchor.recurrenceSeriesId
                ?: throw IllegalStateException("Rezerwacja ${command.appointmentId} nie należy do żadnej serii")

            recurrenceSeriesRepository.findByIdAndStudioId(seriesId, command.studioId.value)
                ?: throw EntityNotFoundException("Seria cykliczna nie została znaleziona")

            val targets = when (command.scope) {
                RecurrenceEditScope.THIS -> listOf(anchor)
                RecurrenceEditScope.THIS_AND_FUTURE -> {
                    val fromIndex = anchor.recurrenceIndex ?: 0
                    appointmentRepository.findBySeriesIdAndIndexGreaterThanEqual(seriesId, fromIndex) +
                        // also include detached future ones
                        appointmentRepository.findBySeriesId(seriesId)
                            .filter { it.isDetached && (it.recurrenceIndex ?: 0) >= fromIndex }
                }
                RecurrenceEditScope.ALL -> appointmentRepository.findBySeriesId(seriesId)
            }

            val now = Instant.now()
            var deletedCount = 0
            var skippedConverted = 0

            for (entity in targets) {
                if (entity.status == AppointmentStatus.CONVERTED) { skippedConverted++; continue }
                entity.deletedAt = now
                entity.updatedBy = command.userId.value
                entity.updatedAt = now
                deletedCount++
            }

            appointmentRepository.saveAll(targets)

            auditService.log(LogAuditCommand(
                studioId = command.studioId,
                userId = command.userId,
                userDisplayName = command.userName ?: "",
                module = AuditModule.APPOINTMENT,
                entityId = seriesId.toString(),
                entityDisplayName = null,
                action = AuditAction.APPOINTMENT_DELETED,
                metadata = mapOf(
                    "scope" to command.scope.name,
                    "deletedCount" to deletedCount.toString(),
                    "skippedConvertedCount" to skippedConverted.toString()
                )
            ))

            DeleteRecurringAppointmentResult(
                deletedCount = deletedCount,
                skippedConvertedCount = skippedConverted
            )
        }
}
