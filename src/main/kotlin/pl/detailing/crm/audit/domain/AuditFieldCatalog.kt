package pl.detailing.crm.audit.domain

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Turns a raw [FieldChange] into something a person can read.
 *
 * Handlers record changes with the field name their domain model uses and the value
 * stringified however it happened to be convenient — `estimatedCompletionDate` /
 * `2026-02-17T09:00:00Z`, `basePriceNet` / `1230.00`. Rendering that requires knowing, per
 * field, what it is called in Polish and what kind of value it holds. Keeping that
 * knowledge here rather than in the frontend means one dictionary instead of one per
 * client, and it cannot silently drift out of sync with the handlers that emit the names.
 *
 * Unknown fields are not an error: they render with a de-camel-cased label and are treated
 * as text, so a handler can add a field without touching this file and the feed degrades
 * gracefully instead of breaking.
 */
object AuditFieldCatalog {

    private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")
    private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    private val DATE_TIME_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm")

    /**
     * No-break space (U+00A0) as the thousands separator and before the currency, so an
     * amount can never be broken across lines. Written as an escape on purpose — as a
     * literal it is indistinguishable from a plain space in a diff or a test.
     */
    private const val NBSP = '\u00A0'

    private const val MASKED = "••••••"

    data class FieldDefinition(val label: String, val type: AuditValueType)

    private val definitions: Map<String, FieldDefinition> = mapOf(
        // Identity / contact
        "firstName" to FieldDefinition("Imię", AuditValueType.TEXT),
        "lastName" to FieldDefinition("Nazwisko", AuditValueType.TEXT),
        "name" to FieldDefinition("Nazwa", AuditValueType.TEXT),
        "customerName" to FieldDefinition("Klient", AuditValueType.TEXT),
        "companyName" to FieldDefinition("Nazwa firmy", AuditValueType.TEXT),
        "email" to FieldDefinition("E-mail", AuditValueType.EMAIL),
        "phone" to FieldDefinition("Telefon", AuditValueType.PHONE),
        "contactIdentifier" to FieldDefinition("Kontakt", AuditValueType.TEXT),
        "address" to FieldDefinition("Adres", AuditValueType.TEXT),
        "taxId" to FieldDefinition("NIP", AuditValueType.TEXT),

        // Vehicle
        "brand" to FieldDefinition("Marka", AuditValueType.TEXT),
        "model" to FieldDefinition("Model", AuditValueType.TEXT),
        "licensePlate" to FieldDefinition("Nr rejestracyjny", AuditValueType.TEXT),
        "yearOfProduction" to FieldDefinition("Rok produkcji", AuditValueType.NUMBER),
        "currentMileage" to FieldDefinition("Przebieg", AuditValueType.NUMBER),
        "color" to FieldDefinition("Kolor", AuditValueType.TEXT),
        "paintType" to FieldDefinition("Rodzaj lakieru", AuditValueType.ENUM),
        "vin" to FieldDefinition("VIN", AuditValueType.TEXT),

        // Visit / appointment
        "title" to FieldDefinition("Tytuł", AuditValueType.TEXT),
        "appointmentTitle" to FieldDefinition("Tytuł rezerwacji", AuditValueType.TEXT),
        "status" to FieldDefinition("Status", AuditValueType.ENUM),
        "estimatedCompletionDate" to FieldDefinition("Planowane zakończenie", AuditValueType.DATE_TIME),
        "startDate" to FieldDefinition("Data rozpoczęcia", AuditValueType.DATE_TIME),
        "endDate" to FieldDefinition("Data zakończenia", AuditValueType.DATE_TIME),
        "deletedAt" to FieldDefinition("Data usunięcia", AuditValueType.DATE_TIME),
        "assignedUserName" to FieldDefinition("Przypisany pracownik", AuditValueType.TEXT),

        // Services / money
        "basePriceNet" to FieldDefinition("Cena bazowa netto", AuditValueType.MONEY),
        "totalNet" to FieldDefinition("Wartość netto", AuditValueType.MONEY),
        "totalGross" to FieldDefinition("Wartość brutto", AuditValueType.MONEY),
        "estimatedValue" to FieldDefinition("Szacowana wartość", AuditValueType.MONEY),
        "vatRate" to FieldDefinition("Stawka VAT", AuditValueType.NUMBER),
        "isPackage" to FieldDefinition("Pakiet", AuditValueType.BOOLEAN),
        "requireManualPrice" to FieldDefinition("Cena ustalana ręcznie", AuditValueType.BOOLEAN),
        "serviceIds" to FieldDefinition("Usługi", AuditValueType.LIST),
        "servicesAdded" to FieldDefinition("Dodane usługi", AuditValueType.LIST),
        "servicesUpdated" to FieldDefinition("Zmienione usługi", AuditValueType.LIST),
        "servicesDeleted" to FieldDefinition("Usunięte usługi", AuditValueType.LIST),
        "items" to FieldDefinition("Pozycje", AuditValueType.LIST),

        // Documents / media
        "fileName" to FieldDefinition("Nazwa pliku", AuditValueType.TEXT),
        "documentName" to FieldDefinition("Nazwa dokumentu", AuditValueType.TEXT),
        "documentNumber" to FieldDefinition("Numer dokumentu", AuditValueType.TEXT),
        "content" to FieldDefinition("Treść", AuditValueType.TEXT),

        // Leads
        "source" to FieldDefinition("Źródło", AuditValueType.ENUM),
        "lostReason" to FieldDefinition("Powód utraty", AuditValueType.TEXT),
        "initialMessage" to FieldDefinition("Wiadomość początkowa", AuditValueType.TEXT),
        "newLeadId" to FieldDefinition("Nowy lead", AuditValueType.REFERENCE),
        "mergedFromLeadId" to FieldDefinition("Scalony lead", AuditValueType.REFERENCE),
        "splitCommentId" to FieldDefinition("Komentarz", AuditValueType.REFERENCE),

        // Accounts / roles
        "account" to FieldDefinition("Konto", AuditValueType.TEXT),
        "accountEmail" to FieldDefinition("E-mail konta", AuditValueType.EMAIL),
        "accountRole" to FieldDefinition("Rola konta", AuditValueType.TEXT),
        "accountIsActive" to FieldDefinition("Konto aktywne", AuditValueType.BOOLEAN),
        "customRoleId" to FieldDefinition("Rola", AuditValueType.REFERENCE),
        "permissions" to FieldDefinition("Liczba uprawnień", AuditValueType.NUMBER),
        "passwordHash" to FieldDefinition("Hasło", AuditValueType.SECRET),

        // Work time
        "workDate" to FieldDefinition("Dzień", AuditValueType.DATE),
        // A formatted duration ("8:00"), not a number — a client that right-aligns numbers
        // would render it wrong.
        "hoursWorked" to FieldDefinition("Czas pracy", AuditValueType.TEXT),
        "startTime" to FieldDefinition("Rozpoczęcie", AuditValueType.TEXT),
        "endTime" to FieldDefinition("Zakończenie", AuditValueType.TEXT),
        "periodStart" to FieldDefinition("Początek okresu", AuditValueType.DATE),
        "periodEnd" to FieldDefinition("Koniec okresu", AuditValueType.DATE),
        "leave" to FieldDefinition("Urlop", AuditValueType.TEXT),

        // Campaigns / communication
        "campaignName" to FieldDefinition("Nazwa kampanii", AuditValueType.TEXT),
        "recipientCount" to FieldDefinition("Liczba odbiorców", AuditValueType.NUMBER),
        "recipient" to FieldDefinition("Odbiorca", AuditValueType.PHONE),
        "recipientName" to FieldDefinition("Odbiorca", AuditValueType.TEXT),
        "subject" to FieldDefinition("Temat", AuditValueType.TEXT),
        "messageBody" to FieldDefinition("Treść wiadomości", AuditValueType.TEXT),
        "failureReason" to FieldDefinition("Powód", AuditValueType.TEXT),

        // Finance
        "documentType" to FieldDefinition("Typ dokumentu", AuditValueType.TEXT),

        // Appointments
        "scheduledDate" to FieldDefinition("Data rezerwacji", AuditValueType.DATE_TIME),

        // Misc
        "meta" to FieldDefinition("Dane dodatkowe", AuditValueType.TEXT)
    )

    /**
     * Enum values worth translating. Statuses are the ones a reader actually sees; the
     * rest render as-is with [AuditValueType.ENUM] so the frontend can map them if it
     * cares. Deliberately not exhaustive — an unmapped code is readable, a wrong
     * translation is not.
     */
    private val enumLabels: Map<String, String> = mapOf(
        // VisitStatus
        "DRAFT" to "Szkic",
        "IN_PROGRESS" to "W trakcie",
        "READY_FOR_PICKUP" to "Gotowe do odbioru",
        "COMPLETED" to "Zakończona",
        "REJECTED" to "Odrzucona",
        "ARCHIVED" to "Zarchiwizowana",
        // AppointmentStatus
        "CREATED" to "Utworzona",
        "ABANDONED" to "Porzucona",
        "CANCELLED" to "Anulowana",
        "CONVERTED" to "Zamieniona na wizytę",
        // VisitServiceStatus
        "PENDING" to "Oczekuje",
        "APPROVED" to "Zaakceptowana",
        "CONFIRMED" to "Potwierdzona"
    )

    fun labelOf(field: String): String = definitions[field]?.label ?: humanize(field)

    /** An explicit [FieldChange.valueType] always wins over what the field name suggests. */
    fun typeOf(change: FieldChange): AuditValueType =
        change.valueType ?: definitions[change.field]?.type ?: AuditValueType.TEXT

    /**
     * Formats a stored value for display. Never throws: a value that does not parse as its
     * declared type falls back to the raw string, because a slightly ugly row beats a
     * failed request.
     */
    fun formatValue(raw: String?, type: AuditValueType): String? {
        if (raw == null) return null
        if (raw.isBlank()) return raw
        if (type == AuditValueType.SECRET) return MASKED

        return when (type) {
            AuditValueType.MONEY -> formatMoney(raw)
            AuditValueType.DATE -> formatDate(raw)
            AuditValueType.DATE_TIME -> formatDateTime(raw)
            AuditValueType.BOOLEAN -> when (raw.lowercase(Locale.ROOT)) {
                "true", "1", "yes" -> "Tak"
                "false", "0", "no" -> "Nie"
                else -> raw
            }
            AuditValueType.ENUM -> enumLabels[raw] ?: raw
            AuditValueType.LIST -> raw.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(", ")
                .ifEmpty { raw }
            else -> raw
        }
    }

    fun render(change: FieldChange): RenderedChange {
        val type = typeOf(change)
        return RenderedChange(
            field = change.field,
            label = labelOf(change.field),
            type = type,
            oldValue = if (type == AuditValueType.SECRET) null else change.oldValue,
            newValue = if (type == AuditValueType.SECRET) null else change.newValue,
            oldValueDisplay = formatValue(change.oldValue, type),
            newValueDisplay = formatValue(change.newValue, type)
        )
    }

    private fun formatMoney(raw: String): String = try {
        val amount = BigDecimal(raw.replace(',', '.').trim()).setScale(2, RoundingMode.HALF_UP)
        val negative = amount.signum() < 0
        val digits = amount.abs().toPlainString()
        val integerPart = digits.substringBefore('.')
        val fractionPart = digits.substringAfter('.', "00")
        val grouped = integerPart.reversed()
            .chunked(3)
            .joinToString(NBSP.toString())
            .reversed()
        "${if (negative) "-" else ""}$grouped,$fractionPart${NBSP}zł"
    } catch (e: NumberFormatException) {
        raw
    }

    private fun formatDate(raw: String): String = parseTemporal(raw)
        ?.let { DATE_FORMAT.format(it.atZone(WARSAW)) }
        ?: raw

    private fun formatDateTime(raw: String): String = parseTemporal(raw)
        ?.let { DATE_TIME_FORMAT.format(it.atZone(WARSAW)) }
        ?: raw

    /** Handlers stringify dates as `Instant`, `LocalDateTime` or `LocalDate`. Accept all three. */
    private fun parseTemporal(raw: String): Instant? {
        val value = raw.trim()
        try {
            return Instant.parse(value)
        } catch (ignored: DateTimeParseException) {
            // not an instant — try the next shape
        }
        try {
            return LocalDateTime.parse(value).atZone(WARSAW).toInstant()
        } catch (ignored: DateTimeParseException) {
            // not a local date-time — try the next shape
        }
        return try {
            LocalDate.parse(value).atStartOfDay(WARSAW).toInstant()
        } catch (ignored: DateTimeParseException) {
            null
        }
    }

    /** `estimatedCompletionDate` -> `Estimated completion date`. */
    private fun humanize(field: String): String {
        if (field.isEmpty()) return field
        val spaced = field
            .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
            .replace('_', ' ')
            .trim()
        return spaced.replaceFirstChar { it.uppercase(Locale.ROOT) }
    }
}

/**
 * A [FieldChange] with everything the UI needs to draw a before/after row.
 *
 * Both the raw and the formatted value are returned: the formatted one to display, the raw
 * one so a client that wants to do its own thing (chart the amounts, diff the text) does
 * not have to parse Polish number formatting back.
 */
data class RenderedChange(
    val field: String,
    val label: String,
    val type: AuditValueType,
    val oldValue: String?,
    val newValue: String?,
    val oldValueDisplay: String?,
    val newValueDisplay: String?
)
