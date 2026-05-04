package pl.detailing.crm.email.template

import org.springframework.stereotype.Component

/**
 * Resolves template variables inside email subject and body templates.
 *
 * Supported placeholders:
 *   {{imie}}          → customer's first name
 *   {{imie_nazwisko}} → customer's full name
 *   {{studio}}        → studio / company name
 *   {{pojazd}}        → vehicle (brand + model)
 *   {{rejestracja}}   → license plate in parentheses, or empty string when absent
 *   {{numer_wizyty}}  → visit number
 */
@Component
class EmailTemplateProcessor {

    fun process(template: String, context: EmailTemplateContext): String {
        val plateToken = if (!context.licensePlate.isNullOrBlank()) " (${context.licensePlate})" else ""

        return template
            .replace("{{imie}}", context.firstName)
            .replace("{{imie_nazwisko}}", context.fullName)
            .replace("{{studio}}", context.studioName)
            .replace("{{pojazd}}", context.vehicleName)
            .replace("{{rejestracja}}", plateToken)
            .replace("{{numer_wizyty}}", context.visitNumber)
    }
}
