package pl.detailing.crm.email.template

/**
 * Data available for substitution inside email subject and body templates.
 *
 * Supported placeholders:
 *   {{imie}}          → customer's first name
 *   {{imie_nazwisko}} → customer's full name (first + last)
 *   {{studio}}        → studio / company name
 *   {{pojazd}}        → vehicle description (brand + model)
 *   {{rejestracja}}   → license plate in parentheses, or empty string when absent
 *   {{numer_wizyty}}  → visit number
 */
data class EmailTemplateContext(
    val firstName: String,
    val fullName: String,
    val studioName: String,
    val vehicleName: String,
    val licensePlate: String?,
    val visitNumber: String
)
