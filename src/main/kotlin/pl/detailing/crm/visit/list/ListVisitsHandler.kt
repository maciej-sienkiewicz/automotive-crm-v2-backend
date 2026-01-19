package pl.detailing.crm.visit.list

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import pl.detailing.crm.appointment.infrastructure.AppointmentColorRepository

@Service
class ListVisitsHandler(
    private val visitRepository: VisitRepository,
    private val customerRepository: CustomerRepository,
    private val vehicleRepository: VehicleRepository,
    private val appointmentColorRepository: AppointmentColorRepository
) {
    @Transactional(readOnly = true)
    suspend fun handle(command: ListVisitsCommand): ListVisitsResult {
        // Get all visits matching the criteria
        val allVisits = if (command.status != null) {
            visitRepository.findByStudioIdAndStatus(
                studioId = command.studioId.value,
                status = command.status
            )
        } else {
            visitRepository.findByStudioId(command.studioId.value)
        }

        // Calculate pagination
        val total = allVisits.size
        val totalPages = if (command.pageSize > 0) {
            (total + command.pageSize - 1) / command.pageSize
        } else {
            1
        }

        // Apply pagination (one-based: page 1 = first page)
        val startIndex = (command.page - 1) * command.pageSize
        val endIndex = minOf(startIndex + command.pageSize, total)
        val visits = if (startIndex >= 0 && startIndex < total) {
            allVisits.subList(startIndex, endIndex)
        } else {
            emptyList()
        }

        // Collect all IDs to fetch in batch
        val customerIds = visits.map { it.customerId }.distinct()
        val vehicleIds = visits.map { it.vehicleId }.distinct()
        val appointmentColorIds = visits.mapNotNull { it.appointmentColorId }.distinct()

        // Batch fetch related entities
        val customers = customerRepository.findAllById(customerIds).associateBy { it.id }
        val vehicles = vehicleRepository.findAllById(vehicleIds).associateBy { it.id }
        val appointmentColors = appointmentColorRepository.findAllById(appointmentColorIds).associateBy { it.id }

        // Map to list items
        val items = visits.map { visit ->
            // Force load lazy collections
            visit.serviceItems.size

            val customer = customers[visit.customerId]
            val vehicle = vehicles[visit.vehicleId]
            val appointmentColor = visit.appointmentColorId?.let { appointmentColors[it] }

            val domain = visit.toDomain()
            val totalNet = domain.calculateTotalNet()
            val totalGross = domain.calculateTotalGross()

            VisitListItem(
                id = visit.id.toString(),
                visitNumber = visit.visitNumber,
                customerId = visit.customerId.toString(),
                vehicleId = visit.vehicleId.toString(),
                customer = VisitCustomerInfo(
                    firstName = customer?.firstName ?: "Unknown",
                    lastName = customer?.lastName ?: "Customer",
                    phone = customer?.phone ?: "",
                    email = customer?.email ?: "",
                    companyName = customer?.companyName
                ),
                vehicle = VisitVehicleInfo(
                    brand = vehicle?.brand ?: visit.brandSnapshot,
                    model = vehicle?.model ?: visit.modelSnapshot,
                    licensePlate = vehicle?.licensePlate ?: visit.licensePlateSnapshot,
                    yearOfProduction = vehicle?.yearOfProduction ?: visit.yearOfProductionSnapshot
                ),
                appointmentColor = appointmentColor?.let { color ->
                    AppointmentColorInfo(
                        id = color.id.toString(),
                        name = color.name,
                        hexColor = color.hexColor
                    )
                },
                services = visit.serviceItems.map { serviceItem ->
                    VisitServiceLineItemInfo(
                        id = serviceItem.id.toString(),
                        serviceId = serviceItem.serviceId.toString(),
                        serviceName = serviceItem.serviceName,
                        basePriceNet = serviceItem.basePriceNet,
                        vatRate = serviceItem.vatRate,
                        adjustment = VisitPriceAdjustmentInfo(
                            type = serviceItem.adjustmentType,
                            value = serviceItem.adjustmentValue
                        ),
                        note = serviceItem.customNote,
                        finalPriceNet = serviceItem.finalPriceNet,
                        finalPriceGross = serviceItem.finalPriceGross,
                        status = serviceItem.status
                    )
                },
                status = visit.status,
                scheduledDate = visit.scheduledDate.toString(),
                completedDate = visit.completedDate?.toString(),
                mileageAtArrival = visit.mileageAtArrival,
                keysHandedOver = visit.keysHandedOver,
                documentsHandedOver = visit.documentsHandedOver,
                totalNet = totalNet.amountInCents,
                totalGross = totalGross.amountInCents,
                createdAt = visit.createdAt.toString(),
                updatedAt = visit.updatedAt.toString()
            )
        }

        return ListVisitsResult(
            items = items,
            total = total,
            page = command.page,
            pageSize = command.pageSize,
            totalPages = totalPages
        )
    }
}

/**
 * Command for listing visits with pagination and filtering
 */
data class ListVisitsCommand(
    val studioId: StudioId,
    val page: Int = 1,
    val pageSize: Int = 20,
    val status: VisitStatus? = null
)

/**
 * Result of listing visits with pagination metadata
 */
data class ListVisitsResult(
    val items: List<VisitListItem>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int
)

data class VisitListItem(
    val id: String,
    val visitNumber: String,
    val customerId: String,
    val vehicleId: String,
    val customer: VisitCustomerInfo,
    val vehicle: VisitVehicleInfo,
    val appointmentColor: AppointmentColorInfo?,
    val services: List<VisitServiceLineItemInfo>,
    val status: VisitStatus,
    val scheduledDate: String,
    val completedDate: String?,
    val mileageAtArrival: Long?,
    val keysHandedOver: Boolean,
    val documentsHandedOver: Boolean,
    val totalNet: Long,
    val totalGross: Long,
    val createdAt: String,
    val updatedAt: String
)

data class VisitCustomerInfo(
    val firstName: String,
    val lastName: String,
    val phone: String,
    val email: String,
    val companyName: String?
)

data class VisitVehicleInfo(
    val brand: String,
    val model: String,
    val licensePlate: String?,
    val yearOfProduction: Int?
)

data class VisitServiceLineItemInfo(
    val id: String,
    val serviceId: String,
    val serviceName: String,
    val basePriceNet: Long,
    val vatRate: Int,
    val adjustment: VisitPriceAdjustmentInfo,
    val note: String?,
    val finalPriceNet: Long,
    val finalPriceGross: Long,
    val status: VisitServiceStatus
)

data class VisitPriceAdjustmentInfo(
    val type: AdjustmentType,
    val value: Long
)

data class AppointmentColorInfo(
    val id: String,
    val name: String,
    val hexColor: String
)
