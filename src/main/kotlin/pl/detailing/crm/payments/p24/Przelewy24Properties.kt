package pl.detailing.crm.payments.p24

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Przelewy24 merchant configuration.
 *
 * [mockMode] — explicit override: when true, skips P24 even if credentials are present.
 *   Defaults to false; the system auto-mocks when [isConfigured] is false (no credentials),
 *   so you only need to set P24_MOCK_MODE=true to force mock when credentials ARE present.
 * [sandbox]  — when true, uses https://sandbox.przelewy24.pl; production otherwise.
 * [merchantId]/[posId]/[crc]/[apiKey] — from the P24 merchant panel (sandbox or live).
 *   When all four are blank/zero the system falls back to mock mode automatically.
 * [frontendBaseUrl] — origin of the SPA; the buyer returns to
 *   {frontendBaseUrl}/payments/result?orderId={id} after completing payment.
 */
@ConfigurationProperties(prefix = "p24")
data class Przelewy24Properties(
    val mockMode: Boolean = false,
    val sandbox: Boolean = true,
    val merchantId: Long = 0,
    val posId: Long = 0,
    val crc: String = "",
    val apiKey: String = "",
    val frontendBaseUrl: String = "https://detailboost.pl",
    val backendBaseUrl: String = "https://api.detailboost.pl",
    val currency: String = "PLN",
    val country: String = "PL",
    val language: String = "pl"
) {
    /** True when all required merchant credentials are present. */
    val isConfigured: Boolean
        get() = merchantId > 0 && posId > 0 && crc.isNotBlank() && apiKey.isNotBlank()

    val apiBaseUrl: String
        get() = if (sandbox) "https://sandbox.przelewy24.pl" else "https://secure.przelewy24.pl"

    fun paymentPageUrl(token: String) = "$apiBaseUrl/trnRequest/$token"
}
