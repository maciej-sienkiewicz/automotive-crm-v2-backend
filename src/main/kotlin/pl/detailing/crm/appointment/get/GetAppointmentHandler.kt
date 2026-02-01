package pl.detailing.crm.appointment.get

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.appointment.infrastructure.AppointmentColorRepository
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.appointment.list.*
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.AppointmentId
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository

@Service
class GetAppointmentHandler(
    private val appointmentRepository: AppointmentRepository,
    private val customerRepository: CustomerRepository,
    private val vehicleRepository: VehicleRepository,
    private val appointmentColorRepository: AppointmentColorRepository
) {
    suspend fun handle(appointmentId: AppointmentId, studioId: StudioId): AppointmentListItem =
        withContext(Dispatchers.IO) {
            val appointment = appointmentRepository.findByIdAndStudioId(appointmentId.value, studioId.value)
                ?: throw NotFoundException("Appointment not found")

            // Fetch related entities
            val customer = customerRepository.findById(appointment.customerId).orElse(null)
            val vehicle = appointment.vehicleId?.let { vehicleRepository.findById(it).orElse(null) }
            val color = appointmentColorRepository.findById(appointment.appointmentColorId).orElse(null)

            val domain = appointment.toDomain()
            val totalNet = domain.calculateTotalNet()
            val totalGross = domain.calculateTotalGross()
            val totalVat = domain.calculateTotalVat()

            AppointmentListItem(
                id = appointment.id.toString(),
                customerId = appointment.customerId.toString(),
                vehicleId = appointment.vehicleId?.toString(),
                customer = CustomerInfo(
                    firstName = customer?.firstName ?: "Unknown",
                    lastName = customer?.lastName ?: "Customer",
                    phone = customer?.phone ?: "",
                    email = customer?.email ?: ""
                ),
                vehicle = vehicle?.let {
                    VehicleInfo(
                        brand = it.brand,
                        model = it.model,
                        year = it.yearOfProduction,
                        licensePlate = it.licensePlate
                    )
                },
                services = appointment.lineItems.map { lineItem ->
                    ServiceLineItemInfo(
                        id = lineItem.id.toString(),
                        serviceId = lineItem.serviceId.toString(),
                        serviceName = lineItem.serviceName,
                        basePriceNet = lineItem.basePriceNet,
                        vatRate = lineItem.vatRate,
                        adjustment = PriceAdjustmentInfo(
                            type = lineItem.adjustmentType,
                            value = lineItem.adjustmentValue
                        ),
                        note = lineItem.customNote,
                        finalPriceNet = lineItem.finalPriceNet,
                        finalPriceGross = lineItem.finalPriceGross
                    )
                },
                schedule = ScheduleInfo(
                    isAllDay = appointment.isAllDay,
                    startDateTime = appointment.startDateTime,
                    endDateTime = appointment.endDateTime
                ),
                appointmentTitle = appointment.appointmentTitle,
                appointmentColor = AppointmentColorInfo(
                    id = appointment.appointmentColorId.toString(),
                    name = color?.name ?: "Unknown",
                    hexColor = color?.hexColor ?: "#808080"
                ),
                status = appointment.status,
                note = appointment.note,
                totalNet = totalNet.amountInCents,
                totalGross = totalGross.amountInCents,
                totalVat = totalVat.amountInCents,
                createdAt = appointment.createdAt,
                updatedAt = appointment.updatedAt
            )
        }
}
