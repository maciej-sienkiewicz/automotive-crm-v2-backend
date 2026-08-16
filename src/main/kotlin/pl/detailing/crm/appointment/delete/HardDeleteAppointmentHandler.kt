package pl.detailing.crm.appointment.delete

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.shared.*
import pl.detailing.crm.smscampaigns.infrastructure.SmsLogJpaRepository
import pl.detailing.crm.smscampaigns.reminder.infrastructure.ScheduledSmsReminderJpaRepository

/**
 * Trwale usuwa rezerwację niezależnie od jej statusu.
 *
 * Usuwa w kolejności:
 * 1. Zaplanowane przypomnienia SMS powiązane z rezerwacją
 * 2. Logi SMS powiązane z rezerwacją
 * 3. Encję rezerwacji (kaskadowo usuwa line items)
 *
 * Blokuje usunięcie rezerwacji o statusie CONVERTED — najpierw należy usunąć powiązaną wizytę.
 */
@Service
class HardDeleteAppointmentHandler(
    private val appointmentRepository: AppointmentRepository,
    private val visitRepository: pl.detailing.crm.visit.infrastructure.VisitRepository,
    private val scheduledSmsReminderJpaRepository: ScheduledSmsReminderJpaRepository,
    private val smsLogJpaRepository: SmsLogJpaRepository,
    private val auditService: AuditService,
    private val transactionTemplate: TransactionTemplate
) {
    @Transactional
    suspend fun handle(command: HardDeleteAppointmentCommand): Unit = withContext(Dispatchers.IO) {
        val appointmentEntity = appointmentRepository.findByIdAndStudioIdIncludingDeleted(
            id = command.appointmentId.value,
            studioId = command.studioId.value
        ) ?: throw EntityNotFoundException("Rezerwacja nie została znaleziona: ${command.appointmentId}")

        // Blokujemy usunięcie jeśli rezerwacja jest skonwertowana do wizyty — wizyta ma NOT NULL FK
        if (appointmentEntity.status == AppointmentStatus.CONVERTED) {
            throw ValidationException(
                "Nie można usunąć rezerwacji o statusie CONVERTED. Najpierw usuń powiązaną wizytę."
            )
        }

        // Rezerwacja z wizytą DRAFT (check-in w toku) ma nadal status CONFIRMED — appointment
        // dostaje CONVERTED dopiero przy potwierdzeniu wizyty. Bez tej walidacji usunięcie
        // rezerwacji zostawia wizytę z wiszącym appointment_id i blokuje jej potwierdzenie.
        val referencingVisit = visitRepository.findByAppointmentIdAndStudioId(
            command.appointmentId.value,
            command.studioId.value
        )
        if (referencingVisit != null) {
            throw ValidationException(
                "Nie można usunąć rezerwacji — istnieje powiązana wizyta " +
                "(#${referencingVisit.visitNumber}). Najpierw anuluj lub usuń wizytę."
            )
        }

        val appointmentTitle = appointmentEntity.appointmentTitle ?: command.appointmentId.value.toString()

        // All three deletions in ONE real transaction (TransactionTemplate — the body of
        // a `@Transactional suspend` function running on Dispatchers.IO escapes the
        // interceptor-managed transaction; see AuditLogWriter). The try/catch blocks
        // below used to swallow a failure and commit whatever had already succeeded,
        // so a booking could survive while its customer's SMS reminders quietly vanished.
        // Letting the exception out now rolls the whole delete back instead.
        transactionTemplate.execute {
            // 1. Usuń zaplanowane przypomnienia SMS
            val reminders = scheduledSmsReminderJpaRepository.findAllByAppointmentId(command.appointmentId.value)
            scheduledSmsReminderJpaRepository.deleteAll(reminders)

            // 2. Usuń logi SMS
            val smsLogs = smsLogJpaRepository.findAllByAppointmentId(command.appointmentId.value)
            smsLogJpaRepository.deleteAll(smsLogs)

            // 3. Usuń rezerwację (kaskadowo usuwa line items)
            appointmentRepository.delete(appointmentEntity)
        }

        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName ?: "",
            module = AuditModule.APPOINTMENT,
            entityId = command.appointmentId.value.toString(),
            entityDisplayName = appointmentTitle,
            action = AuditAction.APPOINTMENT_DELETED,
            changes = listOf(FieldChange("status", appointmentEntity.status.name, "DELETED"))
        ))
    }
}

data class HardDeleteAppointmentCommand(
    val appointmentId: AppointmentId,
    val studioId: StudioId,
    val userId: UserId,
    val userName: String? = null
)
