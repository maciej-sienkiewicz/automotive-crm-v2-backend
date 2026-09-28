package pl.detailing.crm.visit.servicechecks

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditContext
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.visit.infrastructure.VisitServiceItemRepository
import pl.detailing.crm.visit.infrastructure.auditDisplayName
import java.time.Instant
import java.util.UUID

data class ServiceCheck(val serviceItemId: UUID, val checkedAt: Instant, val checkedByName: String?)

/**
 * Odhaczanie wykonanych usług na widoku wizyty.
 *
 * Świadomie bez żadnych reguł: odhaczenie nie zmienia statusu usługi ani wizyty, nie
 * blokuje wydania pojazdu i nikt go nie weryfikuje - to lista kontrolna dla ludzi na hali.
 * Jedyne, czego pilnujemy, to ślad: kto i kiedy odhaczył albo odznaczył, w historii wizyty.
 */
@Service
class VisitServiceChecksService(
    private val checkRepository: VisitServiceCheckRepository,
    private val visitRepository: VisitRepository,
    private val serviceItemRepository: VisitServiceItemRepository,
    private val settingsRepository: StudioSettingsRepository,
    private val auditService: AuditService
) {

    /** Odhaczenia usług, które wciąż są w wizycie - usługa usunięta z wizyty nie wraca z listą. */
    @Transactional(readOnly = true)
    fun list(studioId: StudioId, visitId: VisitId): List<ServiceCheck> {
        val itemIds = serviceItemRepository.findByVisitId(requireVisit(studioId, visitId).id).map { it.id }.toSet()
        return checkRepository.findByStudioIdAndVisitId(studioId.value, visitId.value)
            .filter { it.serviceItemId in itemIds }
            .map { ServiceCheck(it.serviceItemId, it.checkedAt, it.checkedByName) }
    }

    /**
     * Ustawia stan odhaczenia. Idempotentne: ponowne odhaczenie odhaczonej usługi (drugie
     * kliknięcie na tablecie, dwie osoby naraz) nie dopisuje drugiego wpisu do historii.
     *
     * @return odhaczenie po zmianie albo null, gdy usługa jest nieodhaczona
     */
    @Transactional
    fun set(
        studioId: StudioId,
        visitId: VisitId,
        serviceItemId: UUID,
        done: Boolean,
        userId: UserId,
        userName: String
    ): ServiceCheck? {
        val settings = settingsRepository.findById(studioId.value).orElse(null)
        if (settings?.serviceChecklistEnabled != true) {
            throw ConflictException("Odhaczanie usług jest wyłączone w ustawieniach studia.")
        }
        val visit = requireVisit(studioId, visitId)
        val item = serviceItemRepository.findByIdAndVisitId(serviceItemId, visit.id)
            ?: throw EntityNotFoundException("Usługa nie należy do tej wizyty")

        val existing = checkRepository.findById(serviceItemId).orElse(null)
        if (done && existing != null) return existing.toCheck()
        if (!done && existing == null) return null

        val result = if (done) {
            checkRepository.save(
                VisitServiceCheckEntity(
                    serviceItemId = serviceItemId,
                    studioId = studioId.value,
                    visitId = visitId.value,
                    checkedAt = Instant.now(),
                    checkedBy = userId.value,
                    checkedByName = userName.ifBlank { null }?.take(255)
                )
            ).toCheck()
        } else {
            checkRepository.delete(existing!!)
            null
        }

        auditService.logSync(
            LogAuditCommand(
                studioId = studioId,
                userId = userId,
                userDisplayName = userName,
                module = AuditModule.VISIT,
                entityId = visitId.value.toString(),
                entityDisplayName = visit.auditDisplayName,
                action = if (done) AuditAction.SERVICE_CHECKED else AuditAction.SERVICE_UNCHECKED,
                metadata = mapOf("serviceItemId" to serviceItemId.toString(), "serviceName" to item.serviceName),
                context = AuditContext(visitId = visitId, visitName = visit.auditDisplayName)
            )
        )
        return result
    }

    private fun requireVisit(studioId: StudioId, visitId: VisitId) =
        visitRepository.findByIdAndStudioId(visitId.value, studioId.value)
            ?: throw EntityNotFoundException("Wizyta nie została znaleziona")

    private fun VisitServiceCheckEntity.toCheck() = ServiceCheck(serviceItemId, checkedAt, checkedByName)
}
