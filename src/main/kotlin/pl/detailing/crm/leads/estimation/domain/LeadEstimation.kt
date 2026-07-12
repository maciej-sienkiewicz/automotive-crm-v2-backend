package pl.detailing.crm.leads.estimation.domain

import pl.detailing.crm.shared.LeadEstimationId
import pl.detailing.crm.shared.LeadEstimationItemId
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.ServiceId
import pl.detailing.crm.shared.StudioId
import java.time.Instant

data class LeadEstimation(
    val id: LeadEstimationId,
    val leadId: LeadId,
    val studioId: StudioId,
    val extractedNeeds: List<String>,
    val matchedItems: List<LeadEstimationItem>,
    val unmatchedNeeds: List<String>,
    val totalGross: Long,
    val status: LeadEstimationStatus,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class LeadEstimationItem(
    val id: LeadEstimationItemId,
    val serviceId: ServiceId?,
    val serviceName: String,
    val priceNet: Long,
    val vatRate: Int,
    val priceGross: Long,
    val manualPriceRequired: Boolean = false
)

enum class LeadEstimationStatus {
    PENDING,
    COMPLETED,
    FAILED
}
