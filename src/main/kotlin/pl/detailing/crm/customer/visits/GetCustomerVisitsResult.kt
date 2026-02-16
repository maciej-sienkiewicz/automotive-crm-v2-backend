package pl.detailing.crm.customer.visits

import java.math.BigDecimal
import java.time.Instant

data class GetCustomerVisitsResult(
    val visits: List<VisitInfo>,
    val pagination: PaginationInfo
)

data class VisitInfo(
    val id: String,
    val date: Instant,
    val vehicleId: String,
    val vehicleName: String,
    val description: String,
    val totalCost: CostInfo,
    val status: String,
    val createdBy: String,
    val notes: String
)

data class CostInfo(
    val netAmount: BigDecimal,
    val grossAmount: BigDecimal,
    val currency: String
)

data class PaginationInfo(
    val currentPage: Int,
    val totalPages: Int,
    val totalItems: Int,
    val itemsPerPage: Int
)
