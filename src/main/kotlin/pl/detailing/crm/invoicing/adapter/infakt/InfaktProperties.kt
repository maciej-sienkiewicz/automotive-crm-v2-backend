package pl.detailing.crm.invoicing.adapter.infakt

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for the inFakt adapter.
 *
 * Switch between environments in application.properties:
 *
 *   # Sandbox (free test environment):
 *   invoicing.infakt.api-base-url=https://api.sandbox-infakt.pl/api
 *   invoicing.infakt.portal-base-url=https://sandbox.infakt.pl/app/faktury
 *
 *   # Production:
 *   invoicing.infakt.api-base-url=https://api.infakt.pl
 *   invoicing.infakt.portal-base-url=https://app.infakt.pl/app/faktury
 *
 * The adapter appends /v3/invoices.json etc. to [apiBaseUrl], so the value
 * must NOT end with /v3 – include only the root up to (and including) /api
 * for sandbox, or just the host for production.
 */
@ConfigurationProperties(prefix = "invoicing.infakt")
data class InfaktProperties(
    /**
     * Root of the inFakt REST API (without the /v3 segment).
     *   Sandbox:    https://api.sandbox-infakt.pl/api
     *   Production: https://api.infakt.pl
     */
    val apiBaseUrl: String = "https://api.infakt.pl",

    /**
     * Base URL for invoice deep-links on the inFakt web portal.
     *   Sandbox:    https://sandbox.infakt.pl/app/faktury
     *   Production: https://app.infakt.pl/app/faktury
     */
    val portalBaseUrl: String = "https://app.infakt.pl/app/faktury"
)
