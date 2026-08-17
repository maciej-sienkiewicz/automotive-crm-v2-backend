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
 * See [NumberingTemplate] for the placeholder syntax, how the reset period follows
 * from which date tokens are used for {SEQ} templates, and the {RAND} alternative for
 * studios that don't want a visit number to reveal their volume. Either way, this
 * generator does no locking — concurrent draws (two simultaneous {SEQ} reads, or two
 * {RAND} draws that happen to collide) are resolved by
 * [pl.detailing.crm.checkin.CreateVisitFromReservationHandler.persistVisitRetryingOnDuplicateNumber]
 * retrying against the unique (studio_id, visit_number) index — not by this class.
 */
@Service
class VisitNumberGenerator(
    private val visitRepository: VisitRepository,
    private val studioSettingsRepository: StudioSettingsRepository
) {
    companion object {
        const val DEFAULT_FORMAT = "VIS-{YYYY}-{SEQ}"
        const val DEFAULT_SEQUENCE_LENGTH = 5
        const val DEFAULT_RANDOM_LENGTH = 6
    }

    fun generateVisitNumber(studioId: StudioId): String {
        val settings = studioSettingsRepository.findById(studioId.value).orElse(null)
        val template = NumberingTemplate(
            template = settings?.visitNumberFormat?.takeIf { it.isNotBlank() } ?: DEFAULT_FORMAT,
            sequenceLength = settings?.visitNumberSequenceLength ?: DEFAULT_SEQUENCE_LENGTH,
            randomLength = settings?.visitNumberRandomLength ?: DEFAULT_RANDOM_LENGTH
        )

        val today = LocalDate.now()
        return when (template.kind) {
            NumberingTemplate.Kind.RANDOM -> template.renderRandom(today)
            NumberingTemplate.Kind.SEQUENTIAL -> {
                val existingNumbers = visitRepository.findVisitNumbersLike(studioId.value, template.likePattern(today))
                val nextSequence = (existingNumbers.mapNotNull { template.extractSequence(it, today) }.maxOrNull() ?: 0) + 1
                template.render(today, nextSequence)
            }
        }
    }
}
