package pl.detailing.crm.invoicing.domain

/**
 * Normalized invoice status across all external providers.
 * Each adapter is responsible for mapping provider-specific statuses to these values.
 */
enum class ExternalInvoiceStatus(val displayName: String) {
    DRAFT("Szkic"),
    ISSUED("Wystawiona"),
    SENT("Wysłana"),
    PAID("Opłacona"),
    OVERDUE("Przeterminowana"),
    CANCELLED("Anulowana")
}
