package pl.detailing.crm.invoicing.domain

/**
 * Enum representing all supported external invoice providers.
 * Each studio uses exactly one provider.
 */
enum class InvoiceProviderType(val displayName: String) {
    INFAKT("inFakt"),
    WFIRMA("wFirma"),
    IFIRMA("iFirma"),
    FAKTUROWNIA("Fakturownia")
}
