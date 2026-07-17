package pl.detailing.crm.calendar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.doortodoor.infrastructure.DoorToDoorRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VisitStatus
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

enum class DoorToDoorTripDirection {
    PICKUP,
    DELIVERY
}

data class DoorToDoorCalendarEntry(
    val id: String,
    val direction: DoorToDoorTripDirection,
    val vehicle: String,
    val customerLastName: String
)

data class DoorToDoorCalendarDay(
    val date: LocalDate,
    val count: Int,
    val entries: List<DoorToDoorCalendarEntry>
)

/**
 * Dzienna mapa wyjazdów Door to Door dla widoku kalendarza (badge z ikoną samochodu):
 * - odbiór pojazdu od klienta — aktywna rezerwacja z danymi D2D, w dniu jej rozpoczęcia,
 * - dostawa pojazdu do klienta — wizyta z rekordem door_to_door, w dniu planowanego zakończenia.
 */
@Service
class GetDoorToDoorCalendarHandler(
    private val appointmentRepository: AppointmentRepository,
    private val visitRepository: VisitRepository,
    private val doorToDoorRepository: DoorToDoorRepository,
    private val customerRepository: CustomerRepository,
    private val vehicleRepository: VehicleRepository
) {
    companion object {
        private val WARSAW = ZoneId.of("Europe/Warsaw")
    }

    suspend fun handle(studioId: StudioId, from: LocalDate, to: LocalDate): List<DoorToDoorCalendarDay> =
        withContext(Dispatchers.IO) {
            if (to.isBefore(from)) {
                throw ValidationException("Data 'to' nie może być wcześniejsza niż data 'from'")
            }
            if (ChronoUnit.DAYS.between(from, to) > 100) {
                throw ValidationException("Zakres dat nie może przekraczać 100 dni")
            }

            val rangeStart = from.atStartOfDay(WARSAW).toInstant()
            val rangeEnd = to.plusDays(1).atStartOfDay(WARSAW).toInstant()

            val appointments = appointmentRepository.findForCalendar(
                studioId = studioId.value,
                statuses = listOf(AppointmentStatus.CREATED),
                startDate = rangeStart,
                endDate = rangeEnd,
                customerId = null,
                vehicleId = null
            ).filter { it.d2dPickupCity != null || it.d2dDeliveryCity != null }

            val visits = visitRepository.findForCalendar(
                studioId = studioId.value,
                statuses = listOf(
                    VisitStatus.DRAFT,
                    VisitStatus.IN_PROGRESS,
                    VisitStatus.READY_FOR_PICKUP,
                    VisitStatus.COMPLETED
                ),
                startDate = rangeStart,
                endDate = rangeEnd,
                customerId = null,
                vehicleId = null
            )
            val d2dVisitIds = if (visits.isEmpty()) emptySet()
                else doorToDoorRepository.findByVisitIdIn(visits.map { it.id }).map { it.visitId }.toSet()
            val d2dVisits = visits.filter { it.id in d2dVisitIds }

            val customerIds = (appointments.map { it.customerId } + d2dVisits.map { it.customerId }).distinct()
            val vehicleIds = appointments.mapNotNull { it.vehicleId }.distinct()
            val customers = if (customerIds.isEmpty()) emptyMap<UUID, String>()
                else customerRepository.findAllById(customerIds).associate { it.id to (it.lastName ?: "") }
            val vehicles = if (vehicleIds.isEmpty()) emptyMap()
                else vehicleRepository.findAllById(vehicleIds).associateBy { it.id }

            val entriesByDate = mutableMapOf<LocalDate, MutableList<DoorToDoorCalendarEntry>>()

            appointments.forEach { appointment ->
                val day = appointment.startDateTime.atZone(WARSAW).toLocalDate()
                if (day.isBefore(from) || day.isAfter(to)) return@forEach
                val vehicle = appointment.vehicleId?.let { vehicles[it] }
                entriesByDate.getOrPut(day) { mutableListOf() }.add(DoorToDoorCalendarEntry(
                    id = appointment.id.toString(),
                    direction = DoorToDoorTripDirection.PICKUP,
                    vehicle = vehicle?.let { "${it.brand} ${it.model}" } ?: "Pojazd",
                    customerLastName = customers[appointment.customerId] ?: ""
                ))
            }

            d2dVisits.forEach { visit ->
                val day = (visit.estimatedCompletionDate ?: visit.scheduledDate).atZone(WARSAW).toLocalDate()
                if (day.isBefore(from) || day.isAfter(to)) return@forEach
                entriesByDate.getOrPut(day) { mutableListOf() }.add(DoorToDoorCalendarEntry(
                    id = visit.id.toString(),
                    direction = DoorToDoorTripDirection.DELIVERY,
                    vehicle = "${visit.brandSnapshot} ${visit.modelSnapshot}",
                    customerLastName = customers[visit.customerId] ?: ""
                ))
            }

            entriesByDate.entries
                .sortedBy { it.key }
                .map { (date, entries) ->
                    DoorToDoorCalendarDay(date = date, count = entries.size, entries = entries)
                }
        }
}
