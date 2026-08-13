package pl.detailing.crm.communication.template

import pl.detailing.crm.shared.ValidationException

private val CUSTOMER = setOf("imie", "nazwisko")
private val CUSTOMER_FULL = CUSTOMER + setOf("imie_nazwisko")
private val SCHEDULE = setOf("data", "godzina")
private val VEHICLE = setOf("pojazd", "rejestracja")
private val VISIT = setOf("numer_wizyty")
private val LINK = setOf("link")
private val APPOINTMENT = CUSTOMER + SCHEDULE

/**
 * Every message whose text the studio owns, together with the placeholders it may use.
 *
 * A placeholder exists only for a value we cannot know when the template is written —
 * the customer, their car, the visit, a one-time link. Anything the studio already
 * knows about itself (its name, phone, address, website, opening hours) belongs in the
 * template as plain text, so those keys are deliberately absent.
 *
 * Four messages are intentionally not listed here and stay fixed in code, because their
 * wording is dictated by the flow rather than by marketing: the two service-change SMS
 * (the "Odpisz TAK" consent and its notification-only variant), the password reset e-mail
 * and the employee invitation e-mail.
 */
enum class MessageTemplateKind(val allowedPlaceholders: Set<String>) {

    // ── SMS ─────────────────────────────────────────────────────────────────────

    SMS_PRE_VISIT(APPOINTMENT),
    SMS_POST_VISIT(APPOINTMENT),
    SMS_DELAYED_REMINDER(APPOINTMENT),
    SMS_BOOKING_CONFIRMATION(APPOINTMENT),
    SMS_RESCHEDULE_CONFIRMATION(APPOINTMENT),
    SMS_VISIT_READY_FOR_PICKUP(CUSTOMER + VEHICLE + VISIT),
    SMS_VISIT_CARD_LINK(CUSTOMER + VEHICLE + VISIT + SCHEDULE + LINK),
    SMS_RESERVATION_CARD_LINK(CUSTOMER + SCHEDULE + LINK),
    SMS_UPSELL_CONSENT(CUSTOMER + setOf("uslugi", "kwota")),
    SMS_SIGNATURE_REQUEST(CUSTOMER + LINK + setOf("dokument")),

    // ── E-mail ──────────────────────────────────────────────────────────────────

    EMAIL_VISIT_WELCOME(CUSTOMER_FULL + VEHICLE + VISIT + SCHEDULE),
    EMAIL_VISIT_READY_FOR_PICKUP(CUSTOMER_FULL + VEHICLE + VISIT + SCHEDULE),
    EMAIL_VISIT_CARD_LINK(CUSTOMER_FULL + VEHICLE + VISIT + SCHEDULE + LINK),
    EMAIL_RESERVATION_CARD_LINK(CUSTOMER_FULL + SCHEDULE + LINK),
    EMAIL_BATCH_ORDER_CLOSE(setOf("kontrahent", "okres", "kwota_brutto", "liczba_wpisow")),

    // ── Campaigns ───────────────────────────────────────────────────────────────

    CAMPAIGN(
        CUSTOMER + setOf("marka", "model", "ostatnia_usluga", "data_ostatniej_wizyty", "dni_od_wizyty")
    );

    /**
     * Rejects a template the studio is trying to save when it uses a placeholder this
     * message cannot fill. Catching it here means [MessageTemplateRenderer] can never
     * be handed a template it would have to refuse at send time.
     */
    fun validate(template: String, renderer: MessageTemplateRenderer, label: String) {
        val unknown = renderer.placeholdersIn(template) - allowedPlaceholders
        if (unknown.isNotEmpty()) {
            throw ValidationException(
                "$label: nieznane zmienne ${unknown.joinToString(", ") { "{{$it}}" }}. " +
                    "Dostępne: ${allowedPlaceholders.sorted().joinToString(", ") { "{{$it}}" }}"
            )
        }
    }
}
