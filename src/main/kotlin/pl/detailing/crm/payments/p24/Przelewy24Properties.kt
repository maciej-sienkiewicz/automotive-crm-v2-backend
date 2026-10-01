package pl.detailing.crm.payments.p24

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Przelewy24 merchant configuration.
 *
 * [mockMode] — JEDYNY sposób na realizację zamówień bez płatności (lokalnie, w testach).
 *   Musi być ustawiony jawnie (P24_MOCK_MODE=true). Dawniej brak poświadczeń sam włączał
 *   tryb mock — jedna brakująca zmienna środowiskowa przy wdrożeniu rozdawała pakiety
 *   za darmo, bez żadnego sygnału poza logiem INFO (audyt, P3). Teraz brak poświadczeń
 *   bez mocka = checkout odpowiada 503, a start aplikacji loguje ERROR.
 * [sandbox]  — when true, uses https://sandbox.przelewy24.pl; production otherwise.
 *   Środowisko testowe P24 „nie może być używane do transakcji produkcyjnych" — profil
 *   produkcyjny ustawia domyślnie false (application-docker-props.properties).
 * [merchantId]/[posId]/[crc]/[apiKey] — from the P24 merchant panel (sandbox or live).
 * [frontendBaseUrl] — origin of the SPA; the buyer returns to
 *   {frontendBaseUrl}/payments/result?orderId={id} after completing payment.
 * [transactionTimeLimitMinutes] — `timeLimit` rejestracji: ile minut kupujący ma na
 *   rozpoczęcie płatności (0 = bez limitu, maks. 99).
 * [orderExpiryMinutes] — po tylu minutach bez płatności zamówienie PENDING przechodzi w
 *   EXPIRED (po sprawdzeniu stanu w API P24). EXPIRED nadal przyjmuje spóźnioną płatność.
 * [reconcileAfterMinutes] — od jakiego wieku worker pyta P24 o stan zamówienia PENDING.
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
    val language: String = "pl",
    val transactionTimeLimitMinutes: Int = 15,
    val orderExpiryMinutes: Long = 30,
    val reconcileAfterMinutes: Long = 20
) {
    /** True when all required merchant credentials are present. */
    val isConfigured: Boolean
        get() = merchantId > 0 && posId > 0 && crc.isNotBlank() && apiKey.isNotBlank()

    /** Płatności działają: albo prawdziwa bramka, albo jawnie włączony mock. */
    val paymentsEnabled: Boolean
        get() = mockMode || isConfigured

    val apiBaseUrl: String
        get() = if (sandbox) "https://sandbox.przelewy24.pl" else "https://secure.przelewy24.pl"

    fun paymentPageUrl(token: String) = "$apiBaseUrl/trnRequest/$token"
}
