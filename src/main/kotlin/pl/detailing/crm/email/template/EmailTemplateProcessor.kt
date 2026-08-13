package pl.detailing.crm.email.template

import org.springframework.stereotype.Component
import pl.detailing.crm.communication.template.MessageTemplateRenderer

/**
 * Builds the placeholder values for visit-driven e-mail and hands them to the shared
 * [MessageTemplateRenderer]. Substitution itself lives in exactly one place.
 */
@Component
class EmailTemplateProcessor(
    private val renderer: MessageTemplateRenderer
) {

    fun process(template: String, context: EmailTemplateContext): String = renderer.render(
        template,
        mapOf(
            "imie" to context.firstName,
            "nazwisko" to context.lastName,
            "imie_nazwisko" to context.fullName,
            "pojazd" to context.vehicleName,
            "rejestracja" to context.licensePlate.orEmpty(),
            "numer_wizyty" to context.visitNumber
        ) + MessageTemplateRenderer.scheduleValues(context.scheduledAt)
    )
}
