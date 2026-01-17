package pl.detailing.crm.visit.list

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.domain.AdjustmentType
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository

@Service
class ListVisitsHandler(
    private val visitRepository: VisitRepository,
    private val customerRepository: CustomerRepository,
    private val vehicleRepository: VehicleRepository
) {
    @Transactional(readOnly = true)
    suspend fun handle(studioId: StudioId): List<VisitListItem> {
        val visits = visitRepository.findByStudioId(studioId.value)

        // Collect all IDs to fetch in batch
        val customerIds = visits.map { it.customerId }.distinct()
        val vehicleIds = visits.map { it.vehicleId }.distinct()

        // Batch fetch related entities
        val customers = customerRepository.findAllById(customerIds).associateBy { it.id }
        val vehicles = vehicleRepository.findAllById(vehicleIds).associateBy { it.id }

        // Map to list items
        return visits.map { visit ->
            // Force load lazy collections
            visit.serviceItems.size

            val customer = customers[visit.customerId]
            val vehicle = vehicles[visit.vehicleId]

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
    }
}

data class VisitListItem(
    val id: String,
    val visitNumber: String,
    val customerId: String,
    val vehicleId: String,
    val customer: VisitCustomerInfo,
    val vehicle: VisitVehicleInfo,
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
    val licensePlate: String,
    val yearOfProduction: Int
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
