package pl.detailing.crm.invoicing.sync

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.invoicing.InvoiceProviderRegistry
import pl.detailing.crm.invoicing.credentials.InvoicingCredentialsRepository
import pl.detailing.crm.invoicing.domain.*
import pl.detailing.crm.invoicing.infrastructure.ExternalInvoiceRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.shared.VisitServiceStatus
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant
import java.util.UUID

/**
 * Retries a failed provider sync for a [InvoiceProviderSyncStatus.SYNC_FAILED] invoice.
 *
 * Re-reads the original visit data (line items, buyer info from customer) and re-calls
 * the provider adapter to create the invoice. On success the local record is updated with
 * the provider's externalId and status. On failure the error is updated and the invoice
 * remains SYNC_FAILED so the operation can be retried again later.
 *
 * Only invoices linked to a visit ([ExternalInvoiceEntity.visitId] not null) can be retried
 * because we need the original line items to reconstruct the provider request.
 */
@Service
class RetrySyncInvoiceHandler(
    private val invoiceRepository: ExternalInvoiceRepository,
    private val credentialsRepository: InvoicingCredentialsRepository,
    private val providerRegistry: InvoiceProviderRegistry,
    private val visitRepository: VisitRepository,
    private val customerRepository: CustomerRepository
) {
    private val log = LoggerFactory.getLogger(RetrySyncInvoiceHandler::class.java)

    @Transactional
    fun handle(studioId: StudioId, invoiceId: UUID): ExternalInvoice {
        val entity = invoiceRepository.findByStudioIdAndId(studioId.value, invoiceId)
            ?: throw EntityNotFoundException("Faktura o ID $invoiceId nie istnieje")

        if (entity.providerSyncStatus != InvoiceProviderSyncStatus.SYNC_FAILED) {
            throw ValidationException(
                "Tylko faktury ze statusem SYNC_FAILED mogą być ponownie synchronizowane. " +
                "Bieżący status: ${entity.providerSyncStatus.displayName}"
            )
        }

        val visitId = entity.visitId
            ?: throw ValidationException(
                "Ponowna synchronizacja jest możliwa tylko dla faktur powiązanych z wizytą."
            )

        val credentials = credentialsRepository.findByStudioId(studioId.value)
            ?: throw InvoicingCredentialsNotFoundException()

        val provider = providerRegistry.getProvider(credentials.provider)

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
            ?: entity.buyerName

        val request = IssueInvoiceRequest(
            buyerName     = buyerName ?: "",
            buyerNip      = customer?.companyNip ?: entity.buyerNip,
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
            paymentMethod = "CASH",  // default; payment method not stored on entity
            issueDate     = entity.issueDate,
            dueDate       = entity.dueDate,
            currency      = entity.currency,
            notes         = entity.description
        )

        val now = Instant.now()

        return try {
            val snapshot = provider.issueInvoice(credentials.apiKey, request)

            entity.externalId              = snapshot.externalId
            entity.externalNumber          = snapshot.externalNumber
            entity.status                  = snapshot.status
            entity.providerSyncStatus      = InvoiceProviderSyncStatus.SYNCED
            entity.providerSyncError       = null
            entity.providerSyncAttemptedAt = now
            entity.syncedAt                = now
            entity.updatedAt               = now

            val saved = invoiceRepository.save(entity)
            log.info("[Retry] Invoice {} synced to {} as externalId={}", invoiceId, credentials.provider, snapshot.externalId)
            saved.toDomain(provider.getInvoicePortalUrl(snapshot.externalId))
        } catch (ex: Exception) {
            entity.providerSyncStatus      = InvoiceProviderSyncStatus.SYNC_FAILED
            entity.providerSyncError       = ex.message?.take(2000)
            entity.providerSyncAttemptedAt = now
            entity.updatedAt               = now

            val saved = invoiceRepository.save(entity)
            log.warn("[Retry] Invoice {} retry FAILED for provider {}: {}", invoiceId, credentials.provider, ex.message)
            saved.toDomain(null)
        }
    }
}
