package pl.detailing.crm.appointment.infrastructure

import org.springframework.stereotype.Component
import pl.detailing.crm.audit.domain.AuditContext
import pl.detailing.crm.audit.domain.AuditContextResolver
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.shared.AppointmentId
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VehicleId
import java.util.UUID

/**
 * Zdarzenie o rezerwacji → klient i pojazd tej rezerwacji.
 *
 * Dzięki temu wpis „utworzono rezerwację" pojawia się w „Historii zmian" na karcie
 * klienta i pojazdu, choć piszący zdarzenie podał tylko id rezerwacji — patrz
 * [AuditContextResolver]. Rezerwacja może być już w koszu (zdarzenie usunięcia
 * loguje się po soft-delecie), stąd wariant IncludingDeleted.
 */
@Component
class AppointmentAuditContextResolver(
    private val appointmentRepository: AppointmentRepository
) : AuditContextResolver {

    override fun resolve(studioId: StudioId, module: AuditModule, entityId: String): AuditContext? {
        if (module != AuditModule.APPOINTMENT) return null
        val id = runCatching { UUID.fromString(entityId) }.getOrNull() ?: return null
        val appointment = appointmentRepository.findByIdAndStudioIdIncludingDeleted(id, studioId.value)
            ?: return null

        return AuditContext(
            customerId = CustomerId(appointment.customerId),
            vehicleId = appointment.vehicleId?.let { VehicleId(it) },
            appointmentId = AppointmentId(id),
            appointmentName = appointment.appointmentTitle
        )
    }
}
