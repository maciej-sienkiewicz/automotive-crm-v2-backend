package pl.detailing.crm.trends.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "dataforseo")
data class DataForSeoProperties(
    val login: String = "",
    val password: String = "",
    val baseUrl: String = "https://api.dataforseo.com",
    val maxRetries: Int = 3,
    val backoffMillis: Long = 1000,
    val locationName: String = "Poland",
    val type: String = "web"
)
