package pl.detailing.crm.ksef.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "ksef")
data class KsefProperties(
    val baseUrl: String = "https://api.ksef.mf.gov.pl",
    val apiPath: String = "/api/v2",
    val requestTimeoutSeconds: Long = 30,
    /**
     * Host kodów QR (weryfikacja faktury). Osobna domena niż API, ale ta sama
     * rodzina środowisk — puste pole wyprowadza ją z [baseUrl], żeby zmiana
     * środowiska na test/demo nie wymagała pamiętania o drugiej właściwości.
     */
    val qrBaseUrl: String? = null
) {
    /** api.ksef → qr.ksef, api-test.ksef → qr-test.ksef, api-demo.ksef → qr-demo.ksef. */
    fun resolvedQrBaseUrl(): String =
        qrBaseUrl?.takeIf { it.isNotBlank() }
            ?: baseUrl.replace(Regex("://api(-[a-z0-9]+)?\\."), "://qr$1.")
}
