package pl.detailing.crm.vehicle.appointments

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VehicleId
import java.math.BigDecimal
import java.time.Instant

@Service
class GetVehicleAppointmentsHandler(
    private val appointmentRepository: AppointmentRepository,
    private val customerRepository: CustomerRepository
) {
    suspend fun handle(command: GetVehicleAppointmentsCommand): GetVehicleAppointmentsResult =
        withContext(Dispatchers.IO) {
            val allAppointments = appointmentRepository.findByStudioIdAndVehicleId(
                studioId = command.studioId.value,
                vehicleId = command.vehicleId.value
            ).filter { it.status != pl.detailing.crm.appointment.domain.AppointmentStatus.CONVERTED }

            val totalItems = allAppointments.size
            val totalPages = if (command.limit > 0) {
                (totalItems + command.limit - 1) / command.limit
            } else 1
            val start = (command.page - 1) * command.limit
            val end = minOf(start + command.limit, totalItems)

            val paginatedAppointments = if (start in 0 until totalItems) {
                allAppointments.subList(start, end)
            } else {
                emptyList()
            }

            // Batch-load customer names
            val customerIds = paginatedAppointments.map { it.customerId }.distinct()
            val customerNames = customerIds.associateWith { customerId ->
                val customer = customerRepository.findById(customerId).orElse(null)
                if (customer != null) {
                    listOfNotNull(customer.firstName, customer.lastName).joinToString(" ")
                } else ""
            }

            val appointmentInfoList = paginatedAppointments.map { appointment ->
                var totalNetAmount = 0L
                var totalGrossAmount = 0L
                appointment.lineItems.forEach { lineItem ->
                    totalNetAmount += lineItem.finalPriceNet
                    totalGrossAmount += lineItem.finalPriceGross
                }

                VehicleAppointmentInfo(
                    id = appointment.id.toString(),
                    title = appointment.appointmentTitle ?: "",
                    customerId = appointment.customerId.toString(),
                    customerName = customerNames[appointment.customerId] ?: "",
                    startDateTime = appointment.startDateTime,
                    endDateTime = appointment.endDateTime,
                    isAllDay = appointment.isAllDay,
                    status = appointment.status.name.lowercase(),
                    totalCost = VehicleAppointmentCostInfo(
                        netAmount = BigDecimal.valueOf(totalNetAmount).divide(BigDecimal.valueOf(100)),
                        grossAmount = BigDecimal.valueOf(totalGrossAmount).divide(BigDecimal.valueOf(100)),
                        currency = "PLN"
                    ),
                    note = appointment.note ?: "",
                    createdAt = appointment.createdAt
                )
            }

            GetVehicleAppointmentsResult(
                appointments = appointmentInfoList,
                pagination = VehicleAppointmentPaginationInfo(
                    currentPage = command.page,
                    totalPages = totalPages,
                    totalItems = totalItems,
                    itemsPerPage = command.limit
                )
            )
        }
}

data class GetVehicleAppointmentsCommand(
    val vehicleId: VehicleId,
    val studioId: StudioId,
    val page: Int = 1,
    val limit: Int = 10
)

data class GetVehicleAppointmentsResult(
    val appointments: List<VehicleAppointmentInfo>,
    val pagination: VehicleAppointmentPaginationInfo
)

data class VehicleAppointmentInfo(
    val id: String,
    val title: String,
    val customerId: String,
    val customerName: String,
    val startDateTime: Instant,
    val endDateTime: Instant,
    val isAllDay: Boolean,
    val status: String,
    val totalCost: VehicleAppointmentCostInfo,
    val note: String,
    val createdAt: Instant
)

data class VehicleAppointmentCostInfo(
    val netAmount: BigDecimal,
    val grossAmount: BigDecimal,
    val currency: String
)

data class VehicleAppointmentPaginationInfo(
    val currentPage: Int,
    val totalPages: Int,
    val totalItems: Int,
    val itemsPerPage: Int
)
