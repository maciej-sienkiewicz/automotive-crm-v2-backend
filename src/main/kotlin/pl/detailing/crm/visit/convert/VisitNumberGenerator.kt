package pl.detailing.crm.visit.convert

import org.springframework.stereotype.Service
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.numbering.NumberingTemplate
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.LocalDate

/**
 * Generates visit numbers from the studio's configurable format
 * (Ustawienia → Dane firmy → Numeracja wizyt), falling back to the legacy
 * VIS-YYYY-NNNNN shape when the studio hasn't customized it.
 *
 * See [NumberingTemplate] for the placeholder syntax and how the reset period
 * follows from which date tokens are used, and
 * [pl.detailing.crm.checkin.CreateVisitFromReservationHandler.persistVisitRetryingOnDuplicateNumber]
 * for the concurrency handling: this generator reads max+1 without locking, so the
 * unique (studio_id, visit_number) index — not this class — is the actual race guard.
 */
@Service
class VisitNumberGenerator(
    private val visitRepository: VisitRepository,
    private val studioSettingsRepository: StudioSettingsRepository
) {
    companion object {
        const val DEFAULT_FORMAT = "VIS-{YYYY}-{SEQ}"
        const val DEFAULT_SEQUENCE_LENGTH = 5
    }

    fun generateVisitNumber(studioId: StudioId): String {
        val settings = studioSettingsRepository.findById(studioId.value).orElse(null)
        val template = NumberingTemplate(
            template = settings?.visitNumberFormat?.takeIf { it.isNotBlank() } ?: DEFAULT_FORMAT,
            sequenceLength = settings?.visitNumberSequenceLength ?: DEFAULT_SEQUENCE_LENGTH
        )

        val today = LocalDate.now()
        val existingNumbers = visitRepository.findVisitNumbersLike(studioId.value, template.likePattern(today))
        val nextSequence = (existingNumbers.mapNotNull { template.extractSequence(it, today) }.maxOrNull() ?: 0) + 1

        return template.render(today, nextSequence)
    }
}
