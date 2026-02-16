package pl.detailing.crm.customer.visits

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.user.infrastructure.UserRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.math.BigDecimal

@Service
class GetCustomerVisitsHandler(
    private val visitRepository: VisitRepository,
    private val userRepository: UserRepository
) {
    suspend fun handle(command: GetCustomerVisitsCommand): GetCustomerVisitsResult =
        withContext(Dispatchers.IO) {
            // Get all visits for the customer
            val allVisits = visitRepository.findByCustomerIdAndStudioId(
                customerId = command.customerId.value,
                studioId = command.studioId.value
            )

            // Calculate pagination
            val totalItems = allVisits.size
            val totalPages = if (command.limit > 0) {
                (totalItems + command.limit - 1) / command.limit
            } else {
                1
            }
            val start = (command.page - 1) * command.limit
            val end = minOf(start + command.limit, totalItems)

            // Get paginated visits
            val paginatedVisits = if (start < totalItems && start >= 0) {
                allVisits.subList(start, end)
            } else {
                emptyList()
            }

            // Collect unique creator IDs and batch-load user names
            val creatorIds = paginatedVisits.map { it.createdBy }.distinct()
            val creatorNames = creatorIds.associateWith { userId ->
                val user = userRepository.findById(userId).orElse(null)
                if (user != null) "${user.firstName} ${user.lastName}" else ""
            }

            // Map to VisitInfo
            val visitInfoList = paginatedVisits.map { visit ->
                // Calculate total cost
                var totalNetAmount = 0L
                var totalGrossAmount = 0L
                visit.serviceItems.forEach { serviceItem ->
                    totalNetAmount += serviceItem.finalPriceNet
                    totalGrossAmount += serviceItem.finalPriceGross
                }

                // Build vehicle name from snapshots
                val vehicleName = if (visit.licensePlateSnapshot != null) {
                    "${visit.brandSnapshot} ${visit.modelSnapshot} (${visit.licensePlateSnapshot})"
                } else {
                    "${visit.brandSnapshot} ${visit.modelSnapshot}"
                }

                VisitInfo(
                    id = visit.id.toString(),
                    date = visit.scheduledDate,
                    vehicleId = visit.vehicleId.toString(),
                    vehicleName = vehicleName,
                    description = buildDescription(visit.inspectionNotes, visit.technicalNotes),
                    totalCost = CostInfo(
                        netAmount = BigDecimal.valueOf(totalNetAmount).divide(BigDecimal.valueOf(100)),
                        grossAmount = BigDecimal.valueOf(totalGrossAmount).divide(BigDecimal.valueOf(100)),
                        currency = "PLN"
                    ),
                    status = visit.status.name.lowercase(),
                    createdBy = creatorNames[visit.createdBy] ?: "",
                    notes = visit.technicalNotes ?: visit.inspectionNotes ?: ""
                )
            }

            GetCustomerVisitsResult(
                visits = visitInfoList,
                pagination = PaginationInfo(
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
