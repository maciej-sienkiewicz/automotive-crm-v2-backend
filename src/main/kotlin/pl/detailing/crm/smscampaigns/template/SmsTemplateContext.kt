package pl.detailing.crm.smscampaigns.template

import java.time.Instant

/**
 * All data needed to render an appointment-driven SMS template.
 *
 * Kept as a separate value object so [SmsTemplateProcessor] has a single,
 * well-typed input — no stringly-typed Maps, no implicit couplings.
 *
 * There is deliberately no studio name here: the studio knows its own name when it
 * writes the template, so it types it in rather than referencing a placeholder.
 */
data class SmsTemplateContext(
    /** Customer's first name — replaces {{imie}} */
    val firstName: String,
    /** Customer's last name — replaces {{nazwisko}} */
    val lastName: String,
    /** UTC instant of the appointment start — replaces {{data}} and {{godzina}} */
    val appointmentStart: Instant
)
