package pl.detailing.crm.payments.p24

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Przelewy24 merchant configuration.
 *
 * [mockMode] — when true (local/dev default) no call is made to P24: checkout
 *   orders are marked paid immediately and fulfilled synchronously, so the whole
 *   purchase flow can be exercised without merchant credentials.
 * [sandbox]  — when true, uses https://sandbox.przelewy24.pl; production otherwise.
 * [crc]      — CRC key from the P24 merchant panel, used for SHA-384 request signing.
 * [apiKey]   — REST API key (reports key) used as the Basic-auth password; login is [posId].
 * [frontendBaseUrl] — origin of the SPA; the buyer returns to
 *   {frontendBaseUrl}/payments/result?orderId={id} after completing payment.
 */
@ConfigurationProperties(prefix = "p24")
data class Przelewy24Properties(
    val mockMode: Boolean = true,
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
    val apiBaseUrl: String
        get() = if (sandbox) "https://sandbox.przelewy24.pl" else "https://secure.przelewy24.pl"

    fun paymentPageUrl(token: String) = "$apiBaseUrl/trnRequest/$token"
}
