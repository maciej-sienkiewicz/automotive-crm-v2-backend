package pl.detailing.crm.calendar

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.appointment.infrastructure.AppointmentColorRepository
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import pl.detailing.crm.visit.domain.VisitServiceItem
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.visit.infrastructure.VisitServiceItemEntity
import java.time.Instant

data class GetCalendarEventsQuery(
    val studioId: StudioId,
    val startDate: Instant,
    val endDate: Instant,
    val appointmentStatuses: List<AppointmentStatus>,
    val visitStatuses: List<VisitStatus>
)

data class CalendarEventsResult(
    val appointments: List<AppointmentCalendarItem>,
    val visits: List<VisitCalendarItem>
)

@Service
class GetCalendarEventsHandler(
    private val appointmentRepository: AppointmentRepository,
    private val visitRepository: VisitRepository,
    private val customerRepository: CustomerRepository,
    private val vehicleRepository: VehicleRepository,
    private val appointmentColorRepository: AppointmentColorRepository
) {
    suspend fun handle(query: GetCalendarEventsQuery): CalendarEventsResult = withContext(Dispatchers.IO) {
        val appointments = if (query.appointmentStatuses.isEmpty()) emptyList()
        else appointmentRepository.findForCalendar(
            studioId = query.studioId.value,
            statuses = query.appointmentStatuses,
            startDate = query.startDate,
            endDate = query.endDate
        )

        val visits = if (query.visitStatuses.isEmpty()) emptyList()
        else visitRepository.findForCalendar(
            studioId = query.studioId.value,
            statuses = query.visitStatuses,
            startDate = query.startDate,
            endDate = query.endDate
        )

        // Batch-fetch all related entities in one round-trip per type
        val allCustomerIds = (appointments.map { it.customerId } + visits.map { it.customerId }).distinct()
        val allVehicleIds = (appointments.mapNotNull { it.vehicleId } + visits.map { it.vehicleId }).distinct()
        val allColorIds = (appointments.map { it.appointmentColorId } + visits.mapNotNull { it.appointmentColorId }).distinct()

        val customers = if (allCustomerIds.isEmpty()) emptyMap()
                        else customerRepository.findAllById(allCustomerIds).associateBy { it.id }
        val vehicles = if (allVehicleIds.isEmpty()) emptyMap()
                       else vehicleRepository.findAllById(allVehicleIds).associateBy { it.id }
        val colors = if (allColorIds.isEmpty()) emptyMap()
                     else appointmentColorRepository.findAllById(allColorIds).associateBy { it.id }

        val appointmentItems = appointments.map { appointment ->
            val customer = customers[appointment.customerId]
            val vehicle = vehicles[appointment.vehicleId]
            val color = colors[appointment.appointmentColorId]
            val domain = appointment.toDomain()
            val totalNet = domain.calculateTotalNet()
            val totalGross = domain.calculateTotalGross()
            val totalVat = domain.calculateTotalVat()

            AppointmentCalendarItem(
                id = appointment.id.toString(),
                appointmentTitle = appointment.appointmentTitle,
                customerId = appointment.customerId.toString(),
                vehicleId = appointment.vehicleId?.toString(),
                status = appointment.status.name,
                customer = AppointmentCustomerInfo(
                    firstName = customer?.firstName ?: "",
                    lastName = customer?.lastName ?: "",
                    phone = customer?.phone ?: "",
                    email = customer?.email ?: ""
                ),
                vehicle = vehicle?.let {
                    AppointmentVehicleInfo(
                        brand = it.brand,
                        model = it.model,
                        year = it.yearOfProduction,
                        licensePlate = it.licensePlate
                    )
                },
                services = appointment.lineItems.map { lineItem ->
                    AppointmentServiceInfo(
                        id = lineItem.id?.toString() ?: "",
                        serviceId = lineItem.serviceId?.toString(),
                        serviceName = lineItem.serviceName,
                        basePriceNet = lineItem.basePriceNet,
                        vatRate = lineItem.vatRate,
                        finalPriceNet = lineItem.finalPriceNet,
                        finalPriceGross = lineItem.finalPriceGross
                    )
                },
                schedule = AppointmentScheduleInfo(
                    isAllDay = appointment.isAllDay,
                    startDateTime = appointment.startDateTime,
                    endDateTime = appointment.endDateTime
                ),
                appointmentColor = AppointmentColorInfo(
                    id = appointment.appointmentColorId.toString(),
                    name = color?.name ?: "Unknown",
                    hexColor = color?.hexColor ?: "#808080"
                ),
                totalNet = totalNet.amountInCents,
                totalGross = totalGross.amountInCents,
                totalVat = totalVat.amountInCents,
                note = appointment.note
            )
        }

        val visitItems = visits.map { visit ->
            val customer = customers[visit.customerId]
            val vehicle = vehicles[visit.vehicleId]
            val color = visit.appointmentColorId?.let { colors[it] }
            val (totalNet, totalGross) = calculateVisitTotals(visit.serviceItems)

            VisitCalendarItem(
                id = visit.id.toString(),
                title = visit.title,
                visitNumber = visit.visitNumber,
                customerId = visit.customerId.toString(),
                vehicleId = visit.vehicleId.toString(),
                status = visit.status.name,
                scheduledDate = visit.scheduledDate,
                estimatedCompletionDate = visit.estimatedCompletionDate,
                customer = VisitCustomerInfo(
                    firstName = customer?.firstName ?: "",
                    lastName = customer?.lastName ?: "",
                    phone = customer?.phone ?: "",
                    companyName = customer?.companyName
                ),
                vehicle = VisitVehicleInfo(
                    licensePlate = vehicle?.licensePlate ?: visit.licensePlateSnapshot,
                    brand = vehicle?.brand ?: visit.brandSnapshot,
                    model = vehicle?.model ?: visit.modelSnapshot,
                    yearOfProduction = vehicle?.yearOfProduction ?: visit.yearOfProductionSnapshot
                ),
                appointmentColor = color?.let {
                    AppointmentColorInfo(
                        id = it.id.toString(),
                        name = it.name,
                        hexColor = it.hexColor
                    )
                },
                totalNet = totalNet,
                totalGross = totalGross,
                technicalNotes = visit.technicalNotes
            )
        }

        CalendarEventsResult(appointments = appointmentItems, visits = visitItems)
    }

    private fun calculateVisitTotals(entities: List<VisitServiceItemEntity>): Pair<Long, Long> {
        val items = entities.map { it.toDomain() }
        val net = items.fold(Money.ZERO) { acc, item -> acc.plus(effectiveNet(item) ?: Money.ZERO) }
        val gross = items.fold(Money.ZERO) { acc, item -> acc.plus(effectiveGross(item) ?: Money.ZERO) }
        return Pair(net.amountInCents, gross.amountInCents)
    }

    private fun effectiveNet(item: VisitServiceItem): Money? = when {
        item.status == VisitServiceStatus.CONFIRMED || item.status == VisitServiceStatus.APPROVED ->
            item.finalPriceNet
        item.status == VisitServiceStatus.PENDING && item.pendingOperation == PendingOperation.EDIT ->
            item.confirmedSnapshot?.finalPriceNet
        item.status == VisitServiceStatus.PENDING && item.pendingOperation == PendingOperation.DELETE ->
            item.finalPriceNet
        else -> null
    }

    private fun effectiveGross(item: VisitServiceItem): Money? = when {
        item.status == VisitServiceStatus.CONFIRMED || item.status == VisitServiceStatus.APPROVED ->
            item.finalPriceGross
        item.status == VisitServiceStatus.PENDING && item.pendingOperation == PendingOperation.EDIT ->
            item.confirmedSnapshot?.finalPriceGross
        item.status == VisitServiceStatus.PENDING && item.pendingOperation == PendingOperation.DELETE ->
            item.finalPriceGross
        else -> null
    }
}
