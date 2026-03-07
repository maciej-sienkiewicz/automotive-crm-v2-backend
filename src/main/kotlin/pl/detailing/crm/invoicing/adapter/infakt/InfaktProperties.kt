package pl.detailing.crm.invoicing.adapter.infakt

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for the inFakt adapter.
 *
 * Switch between environments by setting these in application.properties:
 *
 *   # Sandbox (demo account)
 *   invoicing.infakt.api-base-url=https://api.infakt.pl
 *   invoicing.infakt.portal-base-url=https://sandbox.infakt.pl/app/faktury
 *
 *   # Production
 *   invoicing.infakt.api-base-url=https://api.infakt.pl
 *   invoicing.infakt.portal-base-url=https://app.infakt.pl/app/faktury
 *
 * Note: inFakt uses the same API base URL for both sandbox and production.
 * The sandbox is a separate account (demo account from infakt.pl/demo).
 * The portal URL differs so that links open in the correct environment.
 */
@ConfigurationProperties(prefix = "invoicing.infakt")
data class InfaktProperties(
    /**
     * Base URL of the inFakt REST API.
     * Production and sandbox use the same URL; the account type is determined by the API key.
     */
    val apiBaseUrl: String = "https://api.infakt.pl",

    /**
     * Base URL for links to invoices on the inFakt web portal.
     * Change to the sandbox portal URL when using a demo account.
     */
    val portalBaseUrl: String = "https://app.infakt.pl/app/faktury"
)
