package pl.detailing.crm.visit.smsreminder

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant

data class SetVisitSmsReminderCommand(
    val studioId: StudioId,
    val visitId: VisitId,
    val suppressed: Boolean
)

data class SetVisitSmsReminderResult(
    val visitId: String,
    val smsReminderSuppressed: Boolean
)

/**
 * Toggles the delayed post-service SMS reminder for a single visit.
 *
 * Sets [VisitEntity.smsReminderSuppressed] directly on the JPA entity so that
 * the [SmsAutomationScheduler] skips this visit when it scans for candidates.
 * No domain state machine transition is involved — this is a simple flag flip.
 */
@Service
class SetVisitSmsReminderHandler(
    private val visitRepository: VisitRepository
) {
    @Transactional
    fun handle(command: SetVisitSmsReminderCommand): SetVisitSmsReminderResult {
        val entity = visitRepository.findByIdAndStudioId(
            id = command.visitId.value,
            studioId = command.studioId.value
        ) ?: throw EntityNotFoundException("Visit not found: ${command.visitId.value}")

        entity.smsReminderSuppressed = command.suppressed
        entity.updatedAt = Instant.now()

        visitRepository.save(entity)

        return SetVisitSmsReminderResult(
            visitId = command.visitId.value.toString(),
            smsReminderSuppressed = command.suppressed
        )
    }
}
