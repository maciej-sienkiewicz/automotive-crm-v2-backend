package pl.detailing.crm.smscampaigns.template

import org.springframework.stereotype.Component
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Resolves template variables inside an SMS message template.
 *
 * Supported placeholders:
 *   {{imie}}    → customer's first name
 *   {{data}}    → appointment date in Polish locale (e.g. "02.04.2026")
 *   {{godzina}} → appointment time in Europe/Warsaw (e.g. "14:30")
 *   {{studio}}  → studio / company name
 *
 * This class has exactly one responsibility: text substitution.
 * It knows nothing about where messages come from or how they are sent.
 */
@Component
class SmsTemplateProcessor {

    private val warsawZone = ZoneId.of("Europe/Warsaw")
    private val dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.forLanguageTag("pl"))
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("pl"))

    fun process(template: String, context: SmsTemplateContext): String {
        val zonedDateTime = context.appointmentStart.atZone(warsawZone)

        return template
            .replace("{{imie}}", context.firstName)
            .replace("{{data}}", dateFormatter.format(zonedDateTime))
            .replace("{{godzina}}", timeFormatter.format(zonedDateTime))
            .replace("{{studio}}", context.studioName)
    }
}
