package pl.detailing.crm.appointment.infrastructure

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditAmount
import pl.detailing.crm.audit.domain.AuditContext
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.AuditValueType
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.leads.appointment.LeadSyncService
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Scheduled job that marks appointments as ABANDONED every 15 minutes.
 *
 * An appointment is considered abandoned when:
 * - Its status is CREATED (not yet converted to a visit, cancelled, or already abandoned)
 * - Its scheduled date is yesterday or earlier (compared in Europe/Warsaw timezone)
 *
 * This prevents stale CREATED appointments from cluttering the calendar.
 */
@Service
class ReservationStatusUpdateJob(
    private val appointmentRepository: AppointmentRepository,
    private val auditService: AuditService,
    private val leadSyncService: LeadSyncService,
    private val customerRepository: CustomerRepository
) {

    companion object {
        private val logger = LoggerFactory.getLogger(ReservationStatusUpdateJob::class.java)

        /** Synthetic system user ID used for audit entries created by automated jobs */
        private val SYSTEM_USER_ID = UserId(UUID.fromString("00000000-0000-0000-0000-000000000000"))
        private const val SYSTEM_USER_NAME = "System"
    }

    /**
     * Marks overdue CREATED appointments as ABANDONED.
     * Runs every 15 minutes.
     * An appointment is considered abandoned when its scheduled date (in Europe/Warsaw timezone)
     * is yesterday or earlier and its status is still CREATED.
     */
    @Scheduled(cron = "0 */15 * * * *")
    @Transactional
    fun markAbandonedReservations() {
        val now = Instant.now()
        logger.info("Starting reservation status update at $now")

        val warsawZone = java.time.ZoneId.of("Europe/Warsaw")
        val startOfToday = java.time.LocalDate.now(warsawZone).atStartOfDay(warsawZone).toInstant()

        val candidates = appointmentRepository.findAbandonedCandidates(startOfToday)

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

                val studioId = StudioId(appointment.studioId)
                val domain = appointment.toDomain()
                val totalGross = domain.calculateTotalGross()
                val customer = try {
                    customerRepository.findByIdAndStudioId(appointment.customerId, appointment.studioId)
                } catch (ex: Exception) {
                    logger.warn("Could not load customer {} for abandoned audit: {}", appointment.customerId, ex.message)
                    null
                }
                val customerName = customer?.let {
                    listOfNotNull(it.firstName, it.lastName).joinToString(" ").takeIf { n -> n.isNotBlank() }
                }

                auditService.logSync(
                    LogAuditCommand(
                        studioId = studioId,
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
                            ),
                            FieldChange(
                                field = "scheduledDate",
                                oldValue = null,
                                newValue = appointment.startDateTime.toString(),
                                valueType = AuditValueType.DATE_TIME
                            )
                        ),
                        amount = if (totalGross.amountInCents > 0)
                            AuditAmount(BigDecimal.valueOf(totalGross.amountInCents, 2))
                        else null,
                        context = AuditContext(
                            customerId = CustomerId(appointment.customerId),
                            customerName = customerName
                        )
                    )
                )

                leadSyncService.markNoShow(
                    appointmentId = appointment.id,
                    studioId = appointment.studioId,
                    userId = SYSTEM_USER_ID.value,
                    userDisplayName = SYSTEM_USER_NAME
                )

                updatedCount++
                logger.debug("Marked appointment ${appointment.id} as ABANDONED (studioId=${appointment.studioId})")
            } catch (e: Exception) {
                failedCount++
                logger.error("Failed to mark appointment ${appointment.id} as ABANDONED: ${e.message}", e)
            }
        }

        logger.info(
            "Reservation status update completed: marked $updatedCount appointment(s) as ABANDONED" +
                if (failedCount > 0) ", failed: $failedCount" else ""
        )
    }
}
