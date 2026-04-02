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
    val enabled: Boolean = false
)
