package pl.detailing.crm.vehicle.visits

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VehicleId
import pl.detailing.crm.user.infrastructure.UserRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.math.BigDecimal
import java.time.Instant

@Service
class GetVehicleVisitsHandler(
    private val visitRepository: VisitRepository,
    private val customerRepository: CustomerRepository,
    private val userRepository: UserRepository
) {
    suspend fun handle(command: GetVehicleVisitsCommand): GetVehicleVisitsResult =
        withContext(Dispatchers.IO) {
            val allVisits = visitRepository.findByVehicleIdAndStudioId(
                vehicleId = command.vehicleId.value,
                studioId = command.studioId.value
            )

            val totalItems = allVisits.size
            val totalPages = if (command.limit > 0) {
                (totalItems + command.limit - 1) / command.limit
            } else 1
            val start = (command.page - 1) * command.limit
            val end = minOf(start + command.limit, totalItems)

            val paginatedVisits = if (start in 0 until totalItems) {
                allVisits.subList(start, end)
            } else {
                emptyList()
            }

            // Batch-load customer names
            val customerIds = paginatedVisits.map { it.customerId }.distinct()
            val customerNames = customerIds.associateWith { customerId ->
                val customer = customerRepository.findById(customerId).orElse(null)
                if (customer != null) {
                    listOfNotNull(customer.firstName, customer.lastName).joinToString(" ")
                } else ""
            }

            // Batch-load creator names
            val creatorIds = paginatedVisits.map { it.createdBy }.distinct()
            val creatorNames = creatorIds.associateWith { userId ->
                val user = userRepository.findById(userId).orElse(null)
                if (user != null) "${user.firstName} ${user.lastName}" else ""
            }

            val visitInfoList = paginatedVisits.map { visit ->
                var totalNetAmount = 0L
                var totalGrossAmount = 0L
                visit.serviceItems.forEach { serviceItem ->
                    totalNetAmount += serviceItem.finalPriceNet
                    totalGrossAmount += serviceItem.finalPriceGross
                }

                VehicleVisitInfo(
                    id = visit.id.toString(),
                    date = visit.scheduledDate,
                    customerId = visit.customerId.toString(),
                    customerName = customerNames[visit.customerId] ?: "",
                    description = buildDescription(visit.inspectionNotes, visit.technicalNotes),
                    totalCost = VehicleVisitCostInfo(
                        netAmount = BigDecimal.valueOf(totalNetAmount).divide(BigDecimal.valueOf(100)),
                        grossAmount = BigDecimal.valueOf(totalGrossAmount).divide(BigDecimal.valueOf(100)),
                        currency = "PLN"
                    ),
                    status = visit.status.name.lowercase(),
                    createdBy = creatorNames[visit.createdBy] ?: "",
                    notes = visit.technicalNotes ?: visit.inspectionNotes ?: ""
                )
            }

            GetVehicleVisitsResult(
                visits = visitInfoList,
                pagination = VehicleVisitPaginationInfo(
                    currentPage = command.page,
                    totalPages = totalPages,
                    totalItems = totalItems,
                    itemsPerPage = command.limit
                )
            )
        }

    private fun buildDescription(inspectionNotes: String?, technicalNotes: String?): String {
        val parts = listOfNotNull(
            inspectionNotes?.takeIf { it.isNotBlank() },
            technicalNotes?.takeIf { it.isNotBlank() }
        )
        return parts.joinToString("; ").ifEmpty { "Wizyta serwisowa" }
    }
}

data class GetVehicleVisitsCommand(
    val vehicleId: VehicleId,
    val studioId: StudioId,
    val page: Int = 1,
    val limit: Int = 10
)

data class GetVehicleVisitsResult(
    val visits: List<VehicleVisitInfo>,
    val pagination: VehicleVisitPaginationInfo
)

data class VehicleVisitInfo(
    val id: String,
    val date: Instant,
    val customerId: String,
    val customerName: String,
    val description: String,
    val totalCost: VehicleVisitCostInfo,
    val status: String,
    val createdBy: String,
    val notes: String
)

data class VehicleVisitCostInfo(
    val netAmount: BigDecimal,
    val grossAmount: BigDecimal,
    val currency: String
)

data class VehicleVisitPaginationInfo(
    val currentPage: Int,
    val totalPages: Int,
    val totalItems: Int,
    val itemsPerPage: Int
)
