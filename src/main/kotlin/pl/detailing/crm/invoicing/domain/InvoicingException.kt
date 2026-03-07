package pl.detailing.crm.invoicing.domain

/**
 * Base class for all invoicing-related exceptions.
 * These are translated to HTTP error responses by the global exception handler.
 */
sealed class InvoicingException(message: String) : RuntimeException(message)

/**
 * Thrown when a studio has not configured credentials for any (or a specific) invoicing provider.
 *
 * Use the no-arg constructor when no provider is configured at all.
 * Use the provider-arg constructor when a specific provider was expected but not configured.
 */
class InvoicingCredentialsNotFoundException private constructor(message: String) : InvoicingException(message) {
    constructor() : this(
        "Brak konfiguracji dostawcy faktur. Skonfiguruj integrację w ustawieniach studia."
    )
    constructor(provider: InvoiceProviderType) : this(
        "Brak konfiguracji dostawcy faktur: ${provider.displayName}. Skonfiguruj klucz API w ustawieniach integracji."
    )
}

/**
 * Thrown when the external provider's API returns an error response.
 *
 * [httpStatus] – HTTP status code from the provider.
 * [providerErrors] – human-readable error messages extracted from the provider's response body.
 */
class InvoicingProviderApiException(
    message: String,
    val httpStatus: Int,
    val providerErrors: List<String> = emptyList()
) : InvoicingException(message) {
    companion object {
        fun unauthorized(provider: InvoiceProviderType): InvoicingProviderApiException =
            InvoicingProviderApiException(
                message = "Nieprawidłowy klucz API dla ${provider.displayName}. Sprawdź konfigurację integracji.",
                httpStatus = 401
            )

        fun validationFailed(provider: InvoiceProviderType, errors: List<String>): InvoicingProviderApiException =
            InvoicingProviderApiException(
                message = "Błąd walidacji faktury w ${provider.displayName}: ${errors.joinToString("; ")}",
                httpStatus = 422,
                providerErrors = errors
            )

        fun serverError(provider: InvoiceProviderType, status: Int): InvoicingProviderApiException =
            InvoicingProviderApiException(
                message = "Błąd serwera ${provider.displayName} (HTTP $status). Spróbuj ponownie za chwilę.",
                httpStatus = status
            )
    }
}

/**
 * Thrown when an invoice with the given external ID is not found locally or at the provider.
 */
class ExternalInvoiceNotFoundException(externalId: String, provider: InvoiceProviderType) :
    InvoicingException("Faktura '$externalId' nie istnieje w ${provider.displayName}.")

/**
 * Thrown when request data fails local validation before being sent to the provider.
 */
class InvoicingValidationException(message: String, val errors: List<String> = emptyList()) :
    InvoicingException(message)

/**
 * Thrown when a studio tries to use a provider adapter that has not been implemented yet.
 */
class InvoicingProviderNotSupportedException(provider: InvoiceProviderType) :
    InvoicingException("Dostawca ${provider.displayName} nie jest jeszcze obsługiwany.")
