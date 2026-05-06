package com.example.demo.trends.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for DataForSEO API integration.
 *
 * Credentials MUST be provided via environment variables or Spring config:
 *   dataforseo.login / dataforseo.password
 *
 * @see <a href="https://docs.dataforseo.com/v3/dataforseo_trends/overview">DataForSEO Trends Overview</a>
 */
@ConfigurationProperties(prefix = "dataforseo")
data class DataForSeoProperties(
    /** DataForSEO account login (email). */
    val login: String = "",
    /** DataForSEO account password. */
    val password: String = "",
    /** Base URL for the DataForSEO API. */
    val baseUrl: String = "https://api.dataforseo.com",
    /** Maximum keywords per single API request (API limit = 5). */
    val maxBatchSize: Int = 5,
    /** Maximum retry attempts for transient errors. */
    val maxRetries: Int = 3,
    /** Initial backoff in milliseconds (doubles on each retry). */
    val backoffMillis: Long = 1000,
    /** Default location name for requests. */
    val locationName: String = "Poland",
    /** Default search type. */
    val type: String = "web"
)

