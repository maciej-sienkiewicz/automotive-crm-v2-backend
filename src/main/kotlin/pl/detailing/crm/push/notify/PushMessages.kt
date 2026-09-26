package pl.detailing.crm.push.notify

import pl.detailing.crm.shared.LeadSource
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The wording of every push notification, as pure functions.
 *
 * A notification is read on a lock screen, often in a single glance: the TITLE is
 * the one line every phone shows in full, the BODY is cut after one or two lines.
 * So the title says what happened and the fact you act on (the time of a booking,
 * the amount earned), and the body carries the detail.
 *
 * Two variants where the customer is involved. A push reaches one person, so it can
 * respect the same rule as every screen: the customer's name and contact are shown
 * only to someone holding `CUSTOMERS_VIEW` (see [PushNotifier]). Everyone else gets
 * the vehicle, which identifies the job at the desk just as well.
 *
 * No middle dot as a separator (CLAUDE.md §4 in the frontend): a lock screen breaks
 * lines anywhere, and "·" at the start of a line reads as nothing. Commas and
 * sentences say how the facts relate.
 *
 * Every notification reads as a REPORT of something that happened in the studio,
 * never as a pitch. Chrome on Android (since 2025) runs an on-device model over the
 * title, body and button texts of every web notification and hides the ones that
 * look like scams behind "Możliwy spam" - with no allowlist for senders and no way
 * to appeal. Studios started seeing that warning; "Właśnie zarobiłeś 1 900,00 zł"
 * was word for word the shape of a money scam. So: no "you won / you earned", no urgency words, no
 * exclamation marks, no emoji, no imperative next to a phone number. The amount
 * stays - as a fact about a visit, not as a promise.
 */
object PushMessages {

    private val polish: Locale = Locale.forLanguageTag("pl-PL")
    private val warsaw: ZoneId = ZoneId.of("Europe/Warsaw")
    private val dayFormat = DateTimeFormatter.ofPattern("EEEE d MMMM", polish)
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", polish)

    /** A notification in both variants; [personal] is null when the two would be identical. */
    data class Message(val masked: PushPayload, val personal: PushPayload? = null)

    // ─── a) Nowy lead ────────────────────────────────────────────────────────

    fun newLead(leadId: String, name: String?, contact: String?, source: LeadSource): Message {
        val base = PushPayload(
            type = PushNotificationType.NEW_LEAD,
            title = "Nowy lead",
            body = "${sourceLabel(source)}.",
            url = "/leads",
            icon = PushIcon.LEAD,
            // Per lead, so a second enquiry never silently replaces the first.
            tag = "lead-$leadId"
        )
        val cleanName = name?.trim()?.takeIf { it.isNotBlank() }
        val cleanContact = contact?.trim()?.takeIf { it.isNotBlank() }
        if (cleanName == null && cleanContact == null) return Message(base)
        return Message(
            masked = base,
            personal = base.copy(
                title = cleanName?.let { "Nowy lead: $it" } ?: base.title,
                body = listOfNotNull(sourceLabel(source), cleanContact).joinToString(", ") + "."
            )
        )
    }

    // ─── b) Nowa rezerwacja ──────────────────────────────────────────────────

    fun reservationCreated(
        appointmentId: String,
        start: Instant,
        allDay: Boolean,
        vehicle: String?,
        serviceNames: List<String>,
        customerName: String?,
        today: LocalDate = LocalDate.now(warsaw)
    ): Message {
        val details = listOfNotNull(vehicle, servicesSummary(serviceNames))
        val base = PushPayload(
            type = PushNotificationType.RESERVATION_CREATED,
            // The date is what the reader acts on ("is that tomorrow?"), so it is the title.
            title = "Nowa rezerwacja: ${whenLabel(start, allDay, today)}",
            body = details.joinToString(". ").ifBlank { "Szczegóły w kalendarzu" } + ".",
            // The calendar opens on the booking's month and highlights it.
            url = "/calendar/rezerwacja/$appointmentId?start=$start",
            icon = PushIcon.RESERVATION,
            tag = "reservation-$appointmentId"
        )
        val name = customerName?.trim()?.takeIf { it.isNotBlank() } ?: return Message(base)
        return Message(
            masked = base,
            personal = base.copy(body = (listOf(name + (vehicle?.let { ", $it" } ?: "")) +
                listOfNotNull(servicesSummary(serviceNames))).joinToString(". ") + ".")
        )
    }

    // ─── c) Przyjęcie pojazdu ────────────────────────────────────────────────

    fun vehicleCheckedIn(
        visitId: String,
        visitNumber: String?,
        brandModel: String?,
        licensePlate: String?,
        customerName: String?
    ): Message {
        val plate = licensePlate?.trim()?.takeIf { it.isNotBlank() }
        val number = visitNumber?.trim()?.takeIf { it.isNotBlank() }?.let { "wizyta nr $it" }
        val base = PushPayload(
            type = PushNotificationType.VEHICLE_CHECKED_IN,
            title = brandModel?.let { "Przyjęto pojazd: $it" } ?: "Przyjęto pojazd",
            body = sentence(listOfNotNull(plate, number)) ?: "Wizyta rozpoczęta.",
            url = "/visits/$visitId",
            icon = PushIcon.CHECKIN,
            tag = "checkin-$visitId"
        )
        val name = customerName?.trim()?.takeIf { it.isNotBlank() } ?: return Message(base)
        return Message(masked = base, personal = base.copy(body = sentence(listOfNotNull(name, plate, number))!!))
    }

    // ─── d) Wydanie pojazdu = zarobek ────────────────────────────────────────

    fun visitCompleted(visitId: String, totalGrossInCents: Long, vehicle: String?, customerName: String?): Message {
        val base = PushPayload(
            type = PushNotificationType.VISIT_COMPLETED,
            // The amount carries the message, so it goes in the title — the one line
            // every phone shows in full, in bold, on the lock screen. Stated as the
            // visit's total, not as "you just earned": that wording is what Chrome's
            // spam model flags (see the class comment).
            title = "Wizyta zakończona: ${formatMoney(totalGrossInCents)}",
            body = vehicle?.let { "Pojazd wydany: $it." } ?: "Pojazd wydany klientowi.",
            url = "/visits/$visitId",
            icon = PushIcon.EARNINGS,
            // Per visit, not per studio: two cars handed over minutes apart are two
            // earnings, and collapsing them would hide one.
            tag = "visit-completed-$visitId"
        )
        val name = customerName?.trim()?.takeIf { it.isNotBlank() } ?: return Message(base)
        return Message(
            masked = base,
            personal = base.copy(body = "Pojazd wydany: " + listOfNotNull(name, vehicle).joinToString(", ") + ".")
        )
    }

    // ─── e) Kampania konkurencji w rejonie ───────────────────────────────────

    /** One studio's news from one refresh; [debut] = the company had never advertised before. */
    data class AreaAdvertiser(val pageId: String, val companyName: String, val newAds: Int, val debut: Boolean)

    fun areaCampaigns(advertisers: List<AreaAdvertiser>, today: LocalDate = LocalDate.now(warsaw)): PushPayload? {
        if (advertisers.isEmpty()) return null
        val single = advertisers.singleOrNull()
        return if (single != null) {
            PushPayload(
                type = PushNotificationType.AREA_CAMPAIGN,
                title = if (single.debut) "Nowa firma reklamuje się w Twoim rejonie" else "Nowa kampania w Twoim rejonie",
                body = "${single.companyName}: ${adsLabel(single.newAds)} na Facebooku i Instagramie.",
                url = "/instagram?widok=reklamy",
                icon = PushIcon.CAMPAIGN,
                // Per company: a second refresh finding more of its ads replaces the
                // first notification instead of stacking a copy.
                tag = "area-campaign-${single.pageId}"
            )
        } else {
            val names = advertisers.sortedByDescending { it.newAds }.map { it.companyName }
            val shown = names.take(2)
            val rest = names.size - shown.size
            PushPayload(
                type = PushNotificationType.AREA_CAMPAIGN,
                title = "Nowe kampanie w Twoim rejonie: ${names.size}",
                body = shown.joinToString(", ") + (if (rest > 0) " i ${firms(rest)}" else "") + ".",
                url = "/instagram?widok=reklamy",
                icon = PushIcon.CAMPAIGN,
                // One summary per day: several refreshes in a morning update it in place.
                tag = "area-campaigns-$today"
            )
        }
    }

    // ─── Pomocnicze ──────────────────────────────────────────────────────────

    /** "BMW X5, WA 12345" - whatever of the three is known. */
    fun vehicleLabel(brand: String?, model: String?, licensePlate: String?): String? {
        val name = listOfNotNull(brand, model).map { it.trim() }.filter { it.isNotBlank() }.joinToString(" ")
        return listOf(name, licensePlate?.trim().orEmpty()).filter { it.isNotBlank() }.joinToString(", ").ifBlank { null }
    }

    /** A person's name, or the company's when the customer is a business with no contact person. */
    fun personName(firstName: String?, lastName: String?, companyName: String?): String? =
        listOfNotNull(firstName, lastName).map { it.trim() }.filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { companyName?.trim()?.takeIf { it.isNotBlank() } }

    fun brandModel(brand: String?, model: String?): String? =
        listOfNotNull(brand, model).map { it.trim() }.filter { it.isNotBlank() }.joinToString(" ").ifBlank { null }

    /** "dziś, 10:00", "jutro, 8:30", "czwartek 2 października, 10:00"; all-day bookings without the hour. */
    internal fun whenLabel(start: Instant, allDay: Boolean, today: LocalDate): String {
        val local = start.atZone(warsaw)
        val day = when (local.toLocalDate()) {
            today -> "dziś"
            today.plusDays(1) -> "jutro"
            else -> dayFormat.format(local)
        }
        return if (allDay) day else "$day, ${timeFormat.format(local)}"
    }

    private fun servicesSummary(names: List<String>): String? {
        val clean = names.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (clean.isEmpty()) return null
        val shown = clean.take(2).joinToString(", ")
        val rest = clean.size - 2
        return if (rest > 0) "$shown i ${plural(rest, "inna usługa", "inne usługi", "innych usług")}" else shown
    }

    private fun sentence(parts: List<String>): String? =
        parts.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { it.replaceFirstChar(Char::uppercase) + "." }

    private fun adsLabel(n: Int) = plural(n, "nowa reklama", "nowe reklamy", "nowych reklam")

    private fun firms(n: Int) = plural(n, "inna firma", "inne firmy", "innych firm")

    /** Polish plural: 1 reklama, 2-4 reklamy, 5+ reklam (but 12-14 reklam, 22 reklamy). */
    internal fun plural(n: Int, one: String, few: String, many: String): String {
        val word = when {
            n == 1 -> one
            n % 10 in 2..4 && n % 100 !in 12..14 -> few
            else -> many
        }
        return "$n $word"
    }

    private fun sourceLabel(source: LeadSource): String = when (source) {
        LeadSource.PHONE -> "Telefon"
        LeadSource.EMAIL -> "E-mail"
        LeadSource.FORM -> "Formularz na stronie"
        LeadSource.MANUAL -> "Dodany ręcznie"
    }

    /**
     * Polish currency formatting: "1 234,50 zł" — non-breaking spaces and a comma,
     * straight from the JDK's pl-PL locale rather than hand-rolled string surgery.
     */
    fun formatMoney(amountInCents: Long): String =
        NumberFormat.getCurrencyInstance(polish)
            .format(BigDecimal(amountInCents).divide(BigDecimal(100), 2, RoundingMode.HALF_UP))
}
