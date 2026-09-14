package pl.detailing.crm.visit.arrival

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant

/**
 * Poprawka „Stanu przy przyjęciu" z widoku wizyty: przebieg, kluczyki, dokumenty.
 *
 * Te trzy pola spisuje się przy ladzie, często w pośpiechu, i literówka w przebiegu
 * (12 400 zamiast 124 000) wychodzi dopiero przy odbiorze albo na protokole. Do tej pory
 * frontend miał przycisk, ale wołał endpoint, którego backend nigdy nie miał — zmiana
 * ginęła po cichu. Każde pole jest opcjonalne: null znaczy „bez zmian", więc UI może
 * poprawić sam przebieg, nie przepisując reszty.
 *
 * Zmiana jest dozwolona niezależnie od statusu wizyty (także po zamknięciu): to korekta
 * zapisu faktu, nie zmiana przebiegu wizyty, a audyt zostawia po niej ślad z wartością
 * przed i po.
 */
@Service
class UpdateArrivalStateHandler(
    private val visitRepository: VisitRepository,
    private val auditService: AuditService
) {
    companion object {
        /** Powyżej tej wartości to na pewno literówka, nie przebieg. */
        const val MAX_MILEAGE_KM = 5_000_000L
    }

    @Transactional
    suspend fun handle(command: UpdateArrivalStateCommand) {
        if (command.mileageAtArrival == null && command.keysHandedOver == null && command.documentsHandedOver == null) {
            throw ValidationException("Nie podano żadnej zmiany stanu przy przyjęciu")
        }
        command.mileageAtArrival?.let { mileage ->
            if (mileage < 0 || mileage > MAX_MILEAGE_KM) {
                throw ValidationException("Przebieg musi mieścić się w zakresie 0–$MAX_MILEAGE_KM km")
            }
        }

        val visit = visitRepository.findByIdAndStudioId(command.visitId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Visit not found: ${command.visitId}")

        val changes = mutableListOf<FieldChange>()
        command.mileageAtArrival?.let { mileage ->
            if (visit.mileageAtArrival != mileage) {
                changes += FieldChange("mileageAtArrival", visit.mileageAtArrival?.toString(), mileage.toString())
                visit.mileageAtArrival = mileage
            }
        }
        command.keysHandedOver?.let { keys ->
            if (visit.keysHandedOver != keys) {
                changes += FieldChange("keysHandedOver", visit.keysHandedOver.toString(), keys.toString())
                visit.keysHandedOver = keys
            }
        }
        command.documentsHandedOver?.let { documents ->
            if (visit.documentsHandedOver != documents) {
                changes += FieldChange("documentsHandedOver", visit.documentsHandedOver.toString(), documents.toString())
                visit.documentsHandedOver = documents
            }
        }
        if (changes.isEmpty()) return

        visit.updatedBy = command.userId.value
        visit.updatedAt = Instant.now()
        visitRepository.save(visit)

        auditService.log(
            LogAuditCommand(
                studioId = command.studioId,
                userId = command.userId,
                userDisplayName = command.userName,
                module = AuditModule.VISIT,
                entityId = command.visitId.value.toString(),
                entityDisplayName = "Wizyta #${visit.visitNumber}",
                action = AuditAction.UPDATE,
                changes = changes,
                metadata = emptyMap()
            )
        )
    }
}

data class UpdateArrivalStateCommand(
    val visitId: VisitId,
    val studioId: StudioId,
    val userId: UserId,
    val userName: String,
    /** Null = bez zmian. */
    val mileageAtArrival: Long?,
    val keysHandedOver: Boolean?,
    val documentsHandedOver: Boolean?
)
