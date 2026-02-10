package pl.detailing.crm.appointment.list

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.appointment.domain.AppointmentStatus
import pl.detailing.crm.appointment.infrastructure.AppointmentColorRepository
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import java.time.Instant
import java.time.LocalDate

@Service
class ListAppointmentsHandler(
    private val appointmentRepository: AppointmentRepository,
    private val customerRepository: CustomerRepository,
    private val vehicleRepository: VehicleRepository,
    private val appointmentColorRepository: AppointmentColorRepository
) {
    suspend fun handle(command: ListAppointmentsCommand): ListAppointmentsResult =
        withContext(Dispatchers.IO) {
            // Create pageable with zero-based indexing (Spring Data uses 0-based, but we accept 1-based from API)
            val pageRequest = PageRequest.of(
                command.page - 1, // Convert to 0-based
                command.pageSize,
                Sort.by(Sort.Direction.DESC, "startDateTime")
            )

            // Execute query with filters and pagination at database level
            val page = appointmentRepository.findAppointmentsWithFilters(
                studioId = command.studioId.value,
                status = command.status,
                searchTerm = command.searchTerm?.takeIf { it.isNotBlank() },
                scheduledDate = command.scheduledDate,
                pageable = pageRequest
            )

            val appointments = page.content

            // Collect all IDs to fetch in batch
            val customerIds = appointments.map { it.customerId }.distinct()
            val vehicleIds = appointments.mapNotNull { it.vehicleId }.distinct()
            val colorIds = appointments.map { it.appointmentColorId }.distinct()

            // Batch fetch related entities
            val customers = customerRepository.findAllById(customerIds).associateBy { it.id }
            val vehicles = vehicleRepository.findAllById(vehicleIds).associateBy { it.id }
            val colors = appointmentColorRepository.findAllById(colorIds).associateBy { it.id }

            // Map to list items
            val items = appointments.map { appointment ->
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
                            year = it.yearOfProduction,
                            licensePlate = it.licensePlate
                        )
                    },
                    services = appointment.lineItems.map { lineItem ->
                        // Convert adjustment value based on type:
                        // - For PERCENT: convert from basis points back to percentage (divide by 100)
                        // - For others: keep as-is (in cents)
                        val adjustmentValue = when (lineItem.adjustmentType) {
                            AdjustmentType.PERCENT -> lineItem.adjustmentValue / 100.0
                            else -> lineItem.adjustmentValue.toDouble()
                        }

                        ServiceLineItemInfo(
                            id = lineItem.id.toString(),
                            serviceId = lineItem.serviceId.toString(),
                            serviceName = lineItem.serviceName,
                            basePriceNet = lineItem.basePriceNet,
                            vatRate = lineItem.vatRate,
                            adjustment = PriceAdjustmentInfo(
                                type = lineItem.adjustmentType,
                                value = adjustmentValue
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

            ListAppointmentsResult(
                items = items,
                total = page.totalElements.toInt(),
                page = command.page, // Return original 1-based page number
                pageSize = command.pageSize,
                totalPages = page.totalPages
            )
        }
}

/**
 * Command for listing appointments with pagination and filtering
 */
data class ListAppointmentsCommand(
    val studioId: StudioId,
    val page: Int = 1,
    val pageSize: Int = 20,
    val status: AppointmentStatus? = null,
    val searchTerm: String? = null,
    val scheduledDate: LocalDate? = null
)

/**
 * Result of listing appointments with pagination metadata
 */
data class ListAppointmentsResult(
    val items: List<AppointmentListItem>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int
)

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
    val note: String?,
    val totalNet: Long,
    val totalGross: Long,
    val totalVat: Long,
    val createdAt: Instant,
    val updatedAt: Instant
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
    val value: Double  // Double to support decimal percentages like -49.19
)

data class ScheduleInfo(
    val isAllDay: Boolean,
    val startDateTime: Instant,
    val endDateTime: Instant
)

data class AppointmentColorInfo(
    val id: String,
    val name: String,
    val hexColor: String
)
