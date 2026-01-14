package pl.detailing.crm.appointment.list

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.appointment.infrastructure.AppointmentColorRepository
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository

@Service
class ListAppointmentsHandler(
    private val appointmentRepository: AppointmentRepository,
    private val customerRepository: CustomerRepository,
    private val vehicleRepository: VehicleRepository,
    private val appointmentColorRepository: AppointmentColorRepository
) {
    suspend fun handle(studioId: StudioId): List<AppointmentListItem> =
        withContext(Dispatchers.IO) {
            val appointments = appointmentRepository.findByStudioId(studioId.value)

            // Collect all IDs to fetch in batch
            val customerIds = appointments.map { it.customerId }.distinct()
            val vehicleIds = appointments.mapNotNull { it.vehicleId }.distinct()
            val colorIds = appointments.map { it.appointmentColorId }.distinct()

            // Batch fetch related entities
            val customers = customerRepository.findAllById(customerIds).associateBy { it.id }
            val vehicles = vehicleRepository.findAllById(vehicleIds).associateBy { it.id }
            val colors = appointmentColorRepository.findAllById(colorIds).associateBy { it.id }

            // Map to list items
            appointments.map { appointment ->
                val customer = customers[appointment.customerId]
                val vehicle = vehicles[appointment.vehicleId]
                val color = colors[appointment.appointmentColorId]

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
                            year = it.year,
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
                        startDateTime = appointment.startDateTime.toString(),
                        endDateTime = appointment.endDateTime.toString()
                    ),
                    appointmentTitle = appointment.appointmentTitle,
                    appointmentColor = AppointmentColorInfo(
                        id = appointment.appointmentColorId.toString(),
                        name = color?.name ?: "Unknown",
                        hexColor = color?.hexColor ?: "#808080"
                    ),
                    status = appointment.status,
                    totalNet = totalNet.amountInCents,
                    totalGross = totalGross.amountInCents,
                    totalVat = totalVat.amountInCents,
                    createdAt = appointment.createdAt.toString(),
                    updatedAt = appointment.updatedAt.toString()
                )
            }
        }
}

data class AppointmentListItem(
    val id: String,
    val customerId: String,
    val vehicleId: String?,
    val customer: CustomerInfo,
    val vehicle: VehicleInfo?,
    val services: List<ServiceLineItemInfo>,
    val schedule: ScheduleInfo,
    val appointmentTitle: String?,
    val appointmentColor: AppointmentColorInfo,
    val status: AppointmentStatus,
    val totalNet: Long,
    val totalGross: Long,
    val totalVat: Long,
    val createdAt: String,
    val updatedAt: String
)

data class CustomerInfo(
    val firstName: String,
    val lastName: String,
    val phone: String,
    val email: String
)

data class VehicleInfo(
    val brand: String,
    val model: String,
    val year: Int?,
    val licensePlate: String?
)

data class ServiceLineItemInfo(
    val id: String,
    val serviceId: String,
    val serviceName: String,
    val basePriceNet: Long,
    val vatRate: Int,
    val adjustment: PriceAdjustmentInfo,
    val note: String?,
    val finalPriceNet: Long,
    val finalPriceGross: Long
)

data class PriceAdjustmentInfo(
    val type: AdjustmentType,
    val value: Long
)

data class ScheduleInfo(
    val isAllDay: Boolean,
    val startDateTime: String,
    val endDateTime: String
)

data class AppointmentColorInfo(
    val id: String,
    val name: String,
    val hexColor: String
)
