package pl.detailing.crm.smscampaigns.template

import org.springframework.stereotype.Component
import pl.detailing.crm.communication.template.MessageTemplateRenderer

/**
 * Builds the placeholder values for appointment-driven SMS and hands them to the
 * shared [MessageTemplateRenderer]. Substitution itself lives in exactly one place.
 *
 * Supported placeholders:
 *   {{imie}}     → customer's first name
 *   {{nazwisko}} → customer's last name
 *   {{data}}     → appointment date in Polish locale (e.g. "02.04.2026")
 *   {{godzina}}  → appointment time in Europe/Warsaw (e.g. "14:30")
 */
@Component
class SmsTemplateProcessor(
    private val renderer: MessageTemplateRenderer
) {

    fun process(template: String, context: SmsTemplateContext): String = renderer.render(
        template,
        mapOf(
            "imie" to context.firstName,
            "nazwisko" to context.lastName
        ) + MessageTemplateRenderer.scheduleValues(context.appointmentStart)
    )
}
