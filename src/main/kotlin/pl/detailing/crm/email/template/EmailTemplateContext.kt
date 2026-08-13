package pl.detailing.crm.email.template

import java.time.Instant

/**
 * Data available for substitution inside email subject and body templates.
 *
 * Supported placeholders:
 *   {{imie}}          → customer's first name
 *   {{nazwisko}}      → customer's last name
 *   {{imie_nazwisko}} → customer's full name (first + last)
 *   {{pojazd}}        → vehicle description (brand + model)
 *   {{rejestracja}}   → license plate, or empty when the car has none
 *   {{numer_wizyty}}  → visit number
 *   {{data}}          → visit date
 *   {{godzina}}       → visit time
 *
 * There is deliberately no studio name here: the studio knows its own name when it
 * writes the template, so it types it in rather than referencing a placeholder.
 */
data class EmailTemplateContext(
    val firstName: String,
    val lastName: String,
    val fullName: String,
    val vehicleName: String,
    val licensePlate: String?,
    val visitNumber: String,
    val scheduledAt: Instant
)
