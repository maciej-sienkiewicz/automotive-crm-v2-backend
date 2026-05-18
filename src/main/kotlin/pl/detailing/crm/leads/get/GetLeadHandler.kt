package pl.detailing.crm.leads.get

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.leads.customer.CustomerSnapshot
import pl.detailing.crm.leads.estimation.infrastructure.LeadEstimationEntity
import pl.detailing.crm.leads.estimation.infrastructure.LeadEstimationRepository
import pl.detailing.crm.leads.estimation.infrastructure.RelatedVisit
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.userquote.infrastructure.LeadUserQuoteRepository
import pl.detailing.crm.leads.userquote.save.SaveUserQuoteResult
import pl.detailing.crm.leads.userquote.save.toResult
import pl.detailing.crm.shared.AppointmentId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import java.time.Instant
import java.util.UUID

data class GetLeadQuery(
    val leadId: LeadId,
    val studioId: StudioId
)

@Service
class GetLeadHandler(
    private val leadRepository: LeadRepository,
    private val leadEstimationRepository: LeadEstimationRepository,
    private val customerRepository: CustomerRepository,
    private val userQuoteRepository: LeadUserQuoteRepository
) {
    suspend fun handle(query: GetLeadQuery): GetLeadResult {
        val leadEntity = withContext(Dispatchers.IO) {
            leadRepository.findById(query.leadId.value).orElse(null)
        } ?: throw EntityNotFoundException("Lead ${query.leadId} nie został znaleziony")

        if (leadEntity.studioId != query.studioId.value) {
            throw ForbiddenException("Lead nie należy do tego studia")
        }

        val estimation = withContext(Dispatchers.IO) {
            leadEstimationRepository.findByLeadId(query.leadId.value)
        }

        val customerSnapshot = withContext(Dispatchers.IO) {
            leadEntity.customerId?.let { customerId ->
                customerRepository.findByIdAndStudioId(customerId, query.studioId.value)
                    ?.let { c ->
                        CustomerSnapshot(
                            id = c.id.toString(),
                            firstName = c.firstName,
                            lastName = c.lastName,
                            email = c.email,
                            phone = c.phone
                        )
                    }
            }
        }

        val userQuote = withContext(Dispatchers.IO) {
            userQuoteRepository.findByLeadId(query.leadId.value)?.toResult()
        }

        return GetLeadResult(
            leadId = query.leadId,
            studioId = query.studioId,
            source = leadEntity.source,
            status = leadEntity.status,
            contactIdentifier = leadEntity.contactIdentifier,
            customerName = leadEntity.customerName,
            initialMessage = leadEntity.initialMessage,
            estimatedValue = leadEntity.estimatedValue,
            requiresVerification = leadEntity.requiresVerification,
            vehicleBrand = leadEntity.vehicleBrand,
            vehicleModel = leadEntity.vehicleModel,
            createdAt = leadEntity.createdAt,
            updatedAt = leadEntity.updatedAt,
            assignedCustomer = customerSnapshot,
            appointmentId = leadEntity.appointmentId?.let { AppointmentId(it) },
            visitId = leadEntity.visitId?.let { VisitId(it) },
            estimation = estimation?.toResult(),
            userQuote = userQuote
        )
    }
}

data class GetLeadResult(
    val leadId: LeadId,
    val studioId: StudioId,
    val source: LeadSource,
    val status: LeadStatus,
    val contactIdentifier: String,
    val customerName: String?,
    val initialMessage: String?,
    val estimatedValue: Long,
    val requiresVerification: Boolean,
    val vehicleBrand: String?,
    val vehicleModel: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val assignedCustomer: CustomerSnapshot?,
    val appointmentId: AppointmentId?,
    val visitId: VisitId?,
    val estimation: EstimationResult?,
    val userQuote: SaveUserQuoteResult?
)

data class EstimationResult(
    val id: UUID,
    val status: String,
    val extractedNeeds: List<String>,
    val matchedItems: List<EstimationItemResult>,
    val unmatchedNeeds: List<String>,
    val totalNet: Long,
    val totalGross: Long,
    val relatedVisits: List<RelatedVisit>,
    val aiSummary: String?,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class EstimationItemResult(
    val serviceId: UUID?,
    val serviceName: String,
    val priceNet: Long,
    val vatRate: Int,
    val priceGross: Long
)

private fun LeadEstimationEntity.toResult() = EstimationResult(
    id = id,
    status = status.name,
    extractedNeeds = extractedNeeds,
    matchedItems = items.map { item ->
        EstimationItemResult(
            serviceId = item.serviceId,
            serviceName = item.serviceName,
            priceNet = item.priceNet,
            vatRate = item.vatRate,
            priceGross = item.priceGross
        )
    },
    unmatchedNeeds = unmatchedNeeds,
    totalNet = items.sumOf { it.priceNet },
    totalGross = totalGross,
    relatedVisits = relatedVisits,
    aiSummary = aiSummary,
    createdAt = createdAt,
    updatedAt = updatedAt
)
