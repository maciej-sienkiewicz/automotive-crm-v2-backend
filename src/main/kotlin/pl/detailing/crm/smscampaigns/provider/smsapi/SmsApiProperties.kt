package pl.detailing.crm.smscampaigns.provider.smsapi

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * SMSAPI integration settings.
 *
 * Set [enabled] = false in non-production environments to log messages without
 * actually calling the external API.
 */
@ConfigurationProperties(prefix = "smsapi")
data class SmsApiProperties(
    /** OAuth2 token issued in the SMSAPI panel. */
    val oauthToken: String = "",
    /** Sender name / alphanumeric ID registered in the SMSAPI panel. Empty = default sender. */
    val senderName: String = "",
    /**
     * SMSAPI gateway URL.
     * PL: https://api.smsapi.pl/
     * COM: https://api.smsapi.com/
     * SE/BG: https://smsapi.io/
     */
    val apiUrl: String = "https://api.smsapi.pl/",
    /** When false the provider logs the message but does NOT call the SMSAPI endpoint. */
    val enabled: Boolean = false,
    /**
     * Phone number whitelist for the testing phase.
     * When non-empty, SMS is only sent to numbers on this list — all others are silently blocked.
     * Empty list = whitelist disabled (all numbers allowed).
     * Format: E.164, e.g. +48888915358
     * Example property: smsapi.whitelist=+48888915358,+48123456789
     */
    val whitelist: List<String> = emptyList()
)
