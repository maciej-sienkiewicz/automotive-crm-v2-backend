package pl.detailing.crm.vehicle.get

import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VehicleId
import pl.detailing.crm.shared.VisitStatus
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerRepository
import pl.detailing.crm.vehicle.infrastructure.VehicleRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.math.BigDecimal

@Service
class GetVehicleDetailHandler(
    private val vehicleRepository: VehicleRepository,
    private val vehicleOwnerRepository: VehicleOwnerRepository,
    private val customerRepository: CustomerRepository,
    private val visitRepository: VisitRepository
) {
    suspend fun handle(command: GetVehicleDetailCommand): GetVehicleDetailResult =
        withContext(Dispatchers.IO) {
            val vehicleEntity = vehicleRepository.findByIdAndStudioId(
                command.vehicleId.value,
                command.studioId.value
            ) ?: throw EntityNotFoundException("Vehicle not found with id: ${command.vehicleId}")

            val owners = vehicleOwnerRepository.findByVehicleId(vehicleEntity.id)

            val ownersInfo = owners.map { ownerEntity ->
                val customer = customerRepository.findById(ownerEntity.id.customerId).orElse(null)
                VehicleOwnerDetail(
                    customerId = ownerEntity.id.customerId.toString(),
                    customerName = if (customer != null) "${customer.firstName} ${customer.lastName}" else "Unknown",
                    role = ownerEntity.ownershipRole.name,
                    assignedAt = ownerEntity.assignedAt
                )
            }

            // Calculate statistics from completed visits
            val visits = visitRepository.findByVehicleIdAndStudioId(vehicleEntity.id, command.studioId.value)
            val completedVisits = visits.filter { it.status == VisitStatus.COMPLETED }

            val totalVisits = completedVisits.size
            val lastVisitDate = completedVisits.maxByOrNull { it.scheduledDate }?.scheduledDate

            var totalNetAmount = 0L
            var totalGrossAmount = 0L

            completedVisits.forEach { visit ->
                visit.serviceItems.forEach { serviceItem ->
                    totalNetAmount += serviceItem.finalPriceNet
                    totalGrossAmount += serviceItem.finalPriceGross
                }
            }

            GetVehicleDetailResult(
                vehicle = VehicleDetail(
                    id = vehicleEntity.id.toString(),
                    licensePlate = vehicleEntity.licensePlate,
                    brand = vehicleEntity.brand,
                    model = vehicleEntity.model,
                    yearOfProduction = vehicleEntity.yearOfProduction,
                    color = vehicleEntity.color,
                    paintType = vehicleEntity.paintType,
                    currentMileage = vehicleEntity.currentMileage.toLong(),
                    status = vehicleEntity.status.name.lowercase(),
                    technicalNotes = "",
                    owners = ownersInfo,
                    stats = VehicleStatsDetail(
                        totalVisits = totalVisits,
                        lastVisitDate = lastVisitDate,
                        totalSpent = MoneyDetail(
                            netAmount = BigDecimal.valueOf(totalNetAmount).divide(BigDecimal.valueOf(100)),
                            grossAmount = BigDecimal.valueOf(totalGrossAmount).divide(BigDecimal.valueOf(100)),
                            currency = "PLN"
                        )
                    ),
                    createdAt = vehicleEntity.createdAt,
                    updatedAt = vehicleEntity.updatedAt,
                    deletedAt = null
                ),
                recentVisits = emptyList(),
                activities = emptyList(),
                photos = emptyList()
            )
        }
}

data class GetVehicleDetailCommand(
    val vehicleId: VehicleId,
    val studioId: StudioId
)

data class GetVehicleDetailResult(
    val vehicle: VehicleDetail,
    val recentVisits: List<VisitSummary>,
    val activities: List<VehicleActivity>,
    val photos: List<VehiclePhoto>
)

data class VehicleDetail(
    val id: String,
    val licensePlate: String?,
    val brand: String,
    val model: String,
    val yearOfProduction: Int?,
    val color: String?,
    val paintType: String?,
    val currentMileage: Long?,
    val status: String,
    val technicalNotes: String,
    val owners: List<VehicleOwnerDetail>,
    val stats: VehicleStatsDetail,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?
)

data class VehicleOwnerDetail(
    val customerId: String,
    val customerName: String,
    val role: String,
    val assignedAt: Instant
)

data class VehicleStatsDetail(
    val totalVisits: Int,
    val lastVisitDate: Instant?,
    val totalSpent: MoneyDetail
)

data class MoneyDetail(
    val netAmount: BigDecimal,
    val grossAmount: BigDecimal,
    val currency: String
)

data class VisitSummary(
    val id: String,
    val date: Instant,
    val type: String,
    val description: String,
    val status: String,
    val totalCost: MoneyDetail,
    val technician: String
)

data class VehicleActivity(
    val id: String,
    val vehicleId: String,
    val type: String,
    val description: String,
    val performedBy: String,
    val performedAt: Instant,
    val metadata: Map<String, Any>
)

data class VehiclePhoto(
    val id: String,
    val vehicleId: String,
    val photoUrl: String,
    val thumbnailUrl: String,
    val description: String,
    val capturedAt: Instant,
    val uploadedAt: Instant,
    val visitId: String?
)
