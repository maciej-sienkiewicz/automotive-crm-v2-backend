package pl.detailing.crm.finance.document

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.finance.domain.FinancialDocument
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.invoicing.InvoicingFacade
import pl.detailing.crm.invoicing.domain.InvoiceItem
import pl.detailing.crm.invoicing.domain.InvoiceProviderSyncStatus
import pl.detailing.crm.invoicing.domain.IssueInvoiceRequest
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VisitServiceStatus
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import java.time.Instant
import java.util.UUID

/**
 * Retries a failed provider sync for a [InvoiceProviderSyncStatus.SYNC_FAILED] document.
 *
 * Re-reads the original visit data (line items, buyer info) and re-calls the provider adapter.
 * On success: the document is updated with the provider's externalId and status SYNCED.
 * On failure: the error is updated and the document remains SYNC_FAILED for another retry.
 *
 * Only documents linked to a visit ([FinancialDocument.visitId] not null) can be retried
 * because we need the original line items to reconstruct the provider request.
 */
@Service
class RetryProviderSyncHandler(
    private val documentRepository: FinancialDocumentRepository,
    private val visitRepository: VisitRepository,
    private val customerRepository: CustomerRepository,
    private val invoicingFacade: InvoicingFacade
) {
    private val log = LoggerFactory.getLogger(RetryProviderSyncHandler::class.java)

    @Transactional
    fun handle(studioId: StudioId, documentId: UUID): FinancialDocument {
        val entity = documentRepository.findByIdAndStudioId(documentId, studioId.value)
            ?: throw EntityNotFoundException("Faktura o ID $documentId nie istnieje")

        if (entity.providerSyncStatus != InvoiceProviderSyncStatus.SYNC_FAILED) {
            throw ValidationException(
                "Ponowna synchronizacja możliwa tylko dla faktur ze statusem SYNC_FAILED. " +
                "Bieżący status: ${entity.providerSyncStatus?.displayName ?: "brak"}"
            )
        }

        val visitId = entity.visitId
            ?: throw ValidationException(
                "Ponowna synchronizacja możliwa tylko dla faktur powiązanych z wizytą."
            )

        val visitEntity = visitRepository.findByIdAndStudioId(visitId, studioId.value)
            ?: throw EntityNotFoundException("Wizyta powiązana z fakturą nie istnieje (visitId=$visitId)")

        visitEntity.serviceItems.size  // force lazy load
        val visit = visitEntity.toDomain()

        val customer = customerRepository.findByIdAndStudioId(visit.customerId.value, studioId.value)

        val billedItems = visit.serviceItems.filter {
            it.status == VisitServiceStatus.CONFIRMED || it.status == VisitServiceStatus.APPROVED
        }

        val buyerName = customer?.companyName?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(customer?.firstName, customer?.lastName)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .takeIf { it.isNotBlank() }
            ?: entity.counterpartyName

        val notesWithVisitId = buildNotes(entity.description, visitId)

        val request = IssueInvoiceRequest(
            buyerName     = buyerName ?: "",
            buyerNip      = customer?.companyNip ?: entity.counterpartyNip,
            buyerEmail    = customer?.email,
            buyerStreet   = customer?.companyAddressStreet ?: customer?.homeAddressStreet,
            buyerCity     = customer?.companyAddressCity ?: customer?.homeAddressCity,
            buyerPostCode = customer?.companyAddressPostalCode ?: customer?.homeAddressPostalCode,
            items         = billedItems.map { item ->
                InvoiceItem(
                    name                = item.serviceName,
                    quantity            = 1.0,
                    unit                = "usł.",
                    unitNetPriceInCents = item.finalPriceNet.amountInCents,
                    vatRate             = item.vatRate.rate
                )
            },
            paymentMethod = entity.paymentMethod.name,
            issueDate     = entity.issueDate,
            dueDate       = entity.dueDate,
            currency      = entity.currency,
            notes         = notesWithVisitId
        )

        val now = Instant.now()

        return try {
            val (_, snapshot) = invoicingFacade.issueInvoice(studioId, request)

            entity.externalId              = snapshot.externalId
            entity.externalNumber          = snapshot.externalNumber
            entity.externalStatus          = snapshot.status
            entity.providerSyncStatus      = InvoiceProviderSyncStatus.SYNCED
            entity.providerSyncError       = null
            entity.providerSyncAttemptedAt = now
            entity.syncedAt                = now
            entity.updatedAt               = now

            val saved = documentRepository.save(entity)
            log.info("[Retry] Document {} synced as externalId={}", documentId, snapshot.externalId)
            saved.toDomain()
        } catch (ex: Exception) {
            entity.providerSyncStatus      = InvoiceProviderSyncStatus.SYNC_FAILED
            entity.providerSyncError       = ex.message?.take(2000)
            entity.providerSyncAttemptedAt = now
            entity.updatedAt               = now

            val saved = documentRepository.save(entity)
            log.warn("[Retry] Document {} retry FAILED: {}", documentId, ex.message)
            saved.toDomain()
        }
    }

    private fun buildNotes(description: String?, visitId: UUID): String {
        val base = description?.takeIf { it.isNotBlank() } ?: ""
        return if (base.isNotBlank()) "$base [crm:visitId:$visitId]"
        else "[crm:visitId:$visitId]"
    }
}
