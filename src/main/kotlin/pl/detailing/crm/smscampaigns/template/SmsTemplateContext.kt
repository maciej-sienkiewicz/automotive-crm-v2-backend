package pl.detailing.crm.smscampaigns.template

import java.time.Instant

/**
 * All data needed to render an SMS template for a single appointment.
 *
 * Kept as a separate value object so [SmsTemplateProcessor] has a single,
 * well-typed input — no stringly-typed Maps, no implicit couplings.
 */
data class SmsTemplateContext(
    /** Customer's first name — replaces {{imie}} */
    val firstName: String,
    /** UTC instant of the appointment start — replaces {{data}} and {{godzina}} */
    val appointmentStart: Instant,
    /** Studio / company display name — replaces {{studio}} */
    val studioName: String
)
