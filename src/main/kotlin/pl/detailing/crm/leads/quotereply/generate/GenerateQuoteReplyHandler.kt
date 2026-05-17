package pl.detailing.crm.leads.quotereply.generate

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.quotereply.domain.QuoteReplyGenerator
import pl.detailing.crm.leads.quotereply.domain.QuoteReplyInput
import pl.detailing.crm.leads.quotereply.domain.QuoteReplyItem
import pl.detailing.crm.leads.quotereply.domain.QuoteReplyResult
import pl.detailing.crm.leads.userquote.infrastructure.LeadUserQuoteRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.StudioId

data class GenerateQuoteReplyQuery(
    val leadId: LeadId,
    val studioId: StudioId
)

@Service
class GenerateQuoteReplyHandler(
    private val leadRepository: LeadRepository,
    private val userQuoteRepository: LeadUserQuoteRepository,
    private val quoteReplyGenerator: QuoteReplyGenerator
) {
    suspend fun handle(query: GenerateQuoteReplyQuery): QuoteReplyResult {
        val leadEntity = withContext(Dispatchers.IO) {
            leadRepository.findById(query.leadId.value).orElse(null)
        } ?: throw EntityNotFoundException("Lead ${query.leadId} not found")

        if (leadEntity.studioId != query.studioId.value) {
            throw ForbiddenException("Lead does not belong to this studio")
        }

        val userQuote = withContext(Dispatchers.IO) {
            userQuoteRepository.findByLeadId(query.leadId.value)
        }

        val quoteItems = userQuote?.items?.map { item ->
            QuoteReplyItem(
                serviceName = item.serviceName,
                priceGross = item.priceGross,
                vatRate = item.vatRate
            )
        } ?: emptyList()

        val input = QuoteReplyInput(
            customerName = leadEntity.customerName,
            initialMessage = leadEntity.initialMessage,
            vehicleBrand = leadEntity.vehicleBrand,
            vehicleModel = leadEntity.vehicleModel,
            quoteItems = quoteItems
        )

        return quoteReplyGenerator.generate(input)
    }
}
