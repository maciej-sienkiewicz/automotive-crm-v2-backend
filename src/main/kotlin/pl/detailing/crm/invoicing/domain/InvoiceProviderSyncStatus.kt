package pl.detailing.crm.invoicing.domain

/**
 * Tracks the synchronization state of an invoice with the external invoicing provider.
 *
 * An invoice is created locally first and then sent to the provider.
 * If the provider call fails, the invoice can be retried later.
 */
enum class InvoiceProviderSyncStatus(val displayName: String) {

    /** Invoice was successfully sent to and confirmed by the external provider. [ExternalInvoiceEntity.externalId] is set. */
    SYNCED("Zsynchronizowana"),

    /** Provider call failed. Invoice exists only locally and can be retried via the retry-sync endpoint. */
    SYNC_FAILED("Błąd synchronizacji")
}
