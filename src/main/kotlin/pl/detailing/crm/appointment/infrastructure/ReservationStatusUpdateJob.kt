package pl.detailing.crm.appointment.infrastructure

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant
import java.util.UUID

/**
 * Scheduled job that marks appointments as ABANDONED every day at 6 AM.
 *
 * An appointment is considered abandoned when:
 * - Its status is CREATED (not yet converted to a visit, cancelled, or already abandoned)
 * - Its start date has already passed
 *
 * This prevents stale CREATED appointments from cluttering the calendar.
 */
@Service
class ReservationStatusUpdateJob(
    private val appointmentRepository: AppointmentRepository,
    private val auditService: AuditService
) {

    companion object {
        private val logger = LoggerFactory.getLogger(ReservationStatusUpdateJob::class.java)

        /** Synthetic system user ID used for audit entries created by automated jobs */
        private val SYSTEM_USER_ID = UserId(UUID.fromString("00000000-0000-0000-0000-000000000000"))
        private const val SYSTEM_USER_NAME = "System"
    }

    /**
     * Marks overdue CREATED appointments as ABANDONED.
     * Runs every day at 06:00.
     */
    @Scheduled(cron = "0 0 6 * * *")
    @Transactional
    fun markAbandonedReservations() {
        val now = Instant.now()
        logger.info("Starting daily reservation status update at $now")

        val candidates = appointmentRepository.findAbandonedCandidates(now)

        if (candidates.isEmpty()) {
            logger.info("No appointments to mark as abandoned")
            return
        }

        logger.info("Found ${candidates.size} appointment(s) to mark as ABANDONED")

        var updatedCount = 0
        var failedCount = 0

        for (appointment in candidates) {
            try {
                appointment.status = AppointmentStatus.ABANDONED
                appointment.updatedAt = now
                appointment.updatedBy = SYSTEM_USER_ID.value

                appointmentRepository.save(appointment)

                auditService.logSync(
                    LogAuditCommand(
                        studioId = StudioId(appointment.studioId),
                        userId = SYSTEM_USER_ID,
                        userDisplayName = SYSTEM_USER_NAME,
                        module = AuditModule.APPOINTMENT,
                        entityId = appointment.id.toString(),
                        entityDisplayName = appointment.appointmentTitle,
                        action = AuditAction.APPOINTMENT_ABANDONED,
                        changes = listOf(
                            FieldChange(
                                field = "status",
                                oldValue = AppointmentStatus.CREATED.name,
                                newValue = AppointmentStatus.ABANDONED.name
                            )
                        )
                    )
                )

                updatedCount++
                logger.debug("Marked appointment ${appointment.id} as ABANDONED (studioId=${appointment.studioId})")
            } catch (e: Exception) {
                failedCount++
                logger.error("Failed to mark appointment ${appointment.id} as ABANDONED: ${e.message}", e)
            }
        }

        logger.info(
            "Daily reservation status update completed: marked $updatedCount appointment(s) as ABANDONED" +
                if (failedCount > 0) ", failed: $failedCount" else ""
        )
    }
}
