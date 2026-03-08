package pl.detailing.crm.invoicing

import org.springframework.stereotype.Service
import pl.detailing.crm.invoicing.credentials.InvoicingCredentialsRepository
import pl.detailing.crm.invoicing.domain.ExternalInvoiceSnapshot
import pl.detailing.crm.invoicing.domain.InvoiceProviderType
import pl.detailing.crm.invoicing.domain.InvoicingCredentialsNotFoundException
import pl.detailing.crm.invoicing.domain.IssueInvoiceRequest
import pl.detailing.crm.shared.StudioId

/**
 * Facade exposing external invoice provider operations to the finance module.
 *
 * This service is the single entry point for any interaction with external invoicing
 * providers (inFakt, wFirma, etc.). It encapsulates credential lookup and adapter
 * resolution so that callers only need to pass a [StudioId] and business-level data.
 *
 * All methods throw [pl.detailing.crm.invoicing.domain.InvoicingException] subclasses
 * on failure. Callers should handle these to implement retry / fallback logic.
 */
@Service
class InvoicingFacade(
    private val credentialsRepository: InvoicingCredentialsRepository,
    private val providerRegistry: InvoiceProviderRegistry
) {

    /**
     * Issues a new invoice via the studio's configured provider.
     *
     * @return Pair of (providerType, snapshot) on success.
     * @throws InvoicingCredentialsNotFoundException if no provider is configured.
     * @throws pl.detailing.crm.invoicing.domain.InvoicingProviderApiException if provider call fails.
     */
    fun issueInvoice(studioId: StudioId, request: IssueInvoiceRequest): Pair<InvoiceProviderType, ExternalInvoiceSnapshot> {
        val credentials = credentialsRepository.findByStudioId(studioId.value)
            ?: throw InvoicingCredentialsNotFoundException()
        val provider = providerRegistry.getProvider(credentials.provider)
        val snapshot = provider.issueInvoice(credentials.apiKey, request)
        return credentials.provider to snapshot
    }

    /**
     * Fetches the current status of a single invoice from the provider's API.
     *
     * @throws InvoicingCredentialsNotFoundException if no provider is configured.
     * @throws pl.detailing.crm.invoicing.domain.InvoicingProviderApiException if provider call fails.
     */
    fun syncInvoiceStatus(studioId: StudioId, provider: InvoiceProviderType, externalId: String): ExternalInvoiceSnapshot {
        val credentials = credentialsRepository.findByStudioId(studioId.value)
            ?: throw InvoicingCredentialsNotFoundException()
        val adapter = providerRegistry.getProvider(provider)
        return adapter.syncInvoiceStatus(credentials.apiKey, externalId)
    }

    /**
     * Fetches all invoices from the studio's configured provider.
     *
     * @return Pair of (providerType, snapshots), or null if no provider is configured.
     */
    fun fetchAllFromProvider(studioId: StudioId): Pair<InvoiceProviderType, List<ExternalInvoiceSnapshot>>? {
        val credentials = credentialsRepository.findByStudioId(studioId.value) ?: return null
        val adapter = providerRegistry.getProvider(credentials.provider)
        val snapshots = adapter.listAllInvoices(credentials.apiKey)
        return credentials.provider to snapshots
    }

    /**
     * Returns the direct URL to view an invoice on the provider's portal.
     */
    fun getPortalUrl(provider: InvoiceProviderType, externalId: String): String =
        providerRegistry.getProvider(provider).getInvoicePortalUrl(externalId)

    /**
     * Marks an invoice as paid on the provider's side.
     *
     * Called when the CRM user changes a document's status to PAID (e.g. after confirming
     * receipt of a bank transfer for a previously PENDING invoice).
     *
     * @param paidDate Optional payment date (YYYY-MM-DD). Passed to the provider as-is.
     * @throws InvoicingCredentialsNotFoundException if no provider is configured.
     */
    fun markInvoiceAsPaid(studioId: StudioId, provider: InvoiceProviderType, externalId: String, paidDate: String?) {
        val credentials = credentialsRepository.findByStudioId(studioId.value)
            ?: throw InvoicingCredentialsNotFoundException()
        val adapter = providerRegistry.getProvider(provider)
        adapter.markAsPaid(credentials.apiKey, externalId, paidDate)
    }

    /**
     * Returns (providerType, apiKey) for the studio, or null if not configured.
     */
    fun findCredentials(studioId: StudioId): Pair<InvoiceProviderType, String>? {
        val creds = credentialsRepository.findByStudioId(studioId.value) ?: return null
        return creds.provider to creds.apiKey
    }
}
