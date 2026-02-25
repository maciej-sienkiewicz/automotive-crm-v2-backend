package pl.detailing.crm.ksef.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "ksef")
data class KsefProperties(
    val baseUrl: String = "https://api-test.ksef.mf.gov.pl",
    val apiPath: String = "/api/v2",
    val requestTimeoutSeconds: Long = 30
)
