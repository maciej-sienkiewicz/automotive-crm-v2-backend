package pl.detailing.crm.visit.infrastructure

import org.springframework.stereotype.Component
import pl.detailing.crm.audit.domain.AuditContext
import pl.detailing.crm.audit.domain.AuditContextResolver
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VehicleId
import pl.detailing.crm.shared.VisitId
import java.util.UUID

/**
 * Zdarzenie o wizycie → klient i pojazd tej wizyty.
 *
 * Ta sama zasada co przy rezerwacjach: wpis „zmiana statusu wizyty" ma być widoczny
 * w „Historii zmian" na karcie klienta i pojazdu, choć piszący podał tylko id
 * wizyty — patrz [AuditContextResolver]. Wariant IncludingDeleted, bo zdarzenie
 * usunięcia loguje się po soft-delecie.
 */
@Component
class VisitAuditContextResolver(
    private val visitRepository: VisitRepository
) : AuditContextResolver {

    override fun resolve(studioId: StudioId, module: AuditModule, entityId: String): AuditContext? {
        if (module != AuditModule.VISIT) return null
        val id = runCatching { UUID.fromString(entityId) }.getOrNull() ?: return null
        val visit = visitRepository.findByIdAndStudioIdIncludingDeleted(id, studioId.value)
            ?: return null

        return AuditContext(
            customerId = CustomerId(visit.customerId),
            vehicleId = VehicleId(visit.vehicleId),
            visitId = VisitId(id),
            visitName = visit.visitNumber
        )
    }
}
