package pl.detailing.crm.smscampaigns.provider.smsapi

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * SMSAPI integration settings.
 *
 * Set [enabled] = false in non-production environments to log messages without
 * actually calling the external API.
 *
 * There is no recipient whitelist here any more: who receives a message is a per-studio
 * decision (the communication redirect switch), applied in the outbound gateway. A global
 * list once shipped in application.properties and blocked every customer number.
 */
@ConfigurationProperties(prefix = "smsapi")
data class SmsApiProperties(
    /** OAuth2 token issued in the SMSAPI panel. */
    val oauthToken: String = "",
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
