package pl.detailing.crm.smscampaigns.reminder

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.shared.normalizePolishPhone
import pl.detailing.crm.smscampaigns.reminder.domain.ScheduledSmsReminder
import pl.detailing.crm.smscampaigns.reminder.domain.ScheduledSmsReminderRepository
import pl.detailing.crm.smscampaigns.reminder.domain.ScheduledSmsReminderStatus
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class ScheduleVisitSmsReminderCommand(
    val studioId: StudioId,
    val visitId: VisitId,
    val userId: UserId,
    /** SMS body — written or accepted by the user (possibly LLM-generated). */
    val messageContent: String,
    /** When to deliver the SMS. Defaults to 90 days from now when null. */
    val scheduledFor: Instant?
)

/**
 * Creates a new PENDING scheduled reminder for a visit.
 *
 * A visit can only have one active PENDING reminder at a time.
 * Throws [ValidationException] if one already exists (the caller must cancel it first,
 * or use [UpdateSmsReminderHandler] to modify it).
 */
@Service
class ScheduleVisitSmsReminderHandler(
    private val visitRepository: VisitRepository,
    private val customerRepository: CustomerRepository,
    private val reminderRepository: ScheduledSmsReminderRepository
) {
    companion object {
        private const val DEFAULT_DELAY_DAYS = 90L
        private const val MAX_MESSAGE_LENGTH = 160
    }

    @Transactional
    fun handle(command: ScheduleVisitSmsReminderCommand): ScheduledSmsReminder {
        validateMessage(command.messageContent)

        val visitEntity = visitRepository.findByIdAndStudioId(
            id = command.visitId.value,
            studioId = command.studioId.value
        ) ?: throw EntityNotFoundException("Wizyta nie znaleziona: ${command.visitId.value}")

        val existing = reminderRepository.findPendingByVisitId(
            visitId = command.visitId.value,
            studioId = command.studioId.value
        )
        if (existing != null) {
            throw ValidationException(
                "Wizyta ma już zaplanowane przypomnienie SMS (ID: ${existing.id}). " +
                "Usuń je lub zaktualizuj przed utworzeniem nowego."
            )
        }

        val customerEntity = customerRepository.findByIdAndStudioId(
            id = visitEntity.customerId,
            studioId = command.studioId.value
        ) ?: throw EntityNotFoundException("Klient nie znaleziony")

        val phone = customerEntity.phone
            ?: throw ValidationException("Klient nie ma zapisanego numeru telefonu")

        val normalizedPhone = normalizePolishPhone(phone)
        val scheduledFor = command.scheduledFor
            ?: Instant.now().plus(DEFAULT_DELAY_DAYS, ChronoUnit.DAYS)

        if (scheduledFor.isBefore(Instant.now())) {
            throw ValidationException("Data wysyłki musi być w przyszłości")
        }

        val reminder = ScheduledSmsReminder(
            id = UUID.randomUUID(),
            studioId = command.studioId.value,
            visitId = command.visitId.value,
            customerId = visitEntity.customerId,
            appointmentId = visitEntity.appointmentId,
            phoneNumber = normalizedPhone,
            messageContent = command.messageContent,
            scheduledFor = scheduledFor,
            status = ScheduledSmsReminderStatus.PENDING,
            sentAt = null,
            externalMessageId = null,
            errorMessage = null,
            createdBy = command.userId.value,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        return reminderRepository.save(reminder)
    }

    private fun validateMessage(content: String) {
        if (content.isBlank()) throw ValidationException("Treść SMS nie może być pusta")
        if (content.length > MAX_MESSAGE_LENGTH) {
            throw ValidationException(
                "Treść SMS przekracza $MAX_MESSAGE_LENGTH znaków (aktualnie: ${content.length})"
            )
        }
    }
}
