package pl.detailing.crm.smscampaigns.domain

import pl.detailing.crm.shared.StudioId

/**
 * A single automation rule controlling when and what SMS to send.
 *
 * [offsetMinutes] is interpreted as:
 *   - PRE_VISIT:        minutes *before* appointment start
 *   - POST_VISIT:       minutes *after* appointment end
 *   - DELAYED_REMINDER: minutes *after* the vehicle was collected
 */
data class SmsAutomationRule(
    val enabled: Boolean,
    val offsetMinutes: Int,
    val messageTemplate: String
) {
    /** No template, no message. Nothing is ever substituted for a blank one. */
    val sendable: Boolean get() = enabled && messageTemplate.isNotBlank()
}

/**
 * A single event-triggered notification rule (no time offset — fired immediately on event).
 */
data class SmsNotificationRule(
    val enabled: Boolean,
    val messageTemplate: String
) {
    /** No template, no message. Nothing is ever substituted for a blank one. */
    val sendable: Boolean get() = enabled && messageTemplate.isNotBlank()
}

/**
 * Per-studio configuration for automated SMS sending.
 *
 * One instance exists per studio. A studio with no row sends nothing — [defaultFor]
 * exists only to seed the editor with a starting point, never to stand in for a
 * template at send time.
 */
data class SmsAutomationConfig(
    val studioId: StudioId,
    val preVisit: SmsAutomationRule,
    val postVisit: SmsAutomationRule,
    val delayedReminder: SmsAutomationRule,
    val bookingConfirmation: SmsNotificationRule,
    val rescheduleConfirmation: SmsNotificationRule,
    val visitReadyForPickup: SmsNotificationRule,
    val visitCardLink: SmsNotificationRule,
    val reservationCardLink: SmsNotificationRule,
    val upsellConsent: SmsNotificationRule,
    val signatureRequest: SmsNotificationRule
) {
    companion object {
        private const val DEFAULT_PRE_VISIT_OFFSET = 60
        private const val DEFAULT_POST_VISIT_OFFSET = 30
        // Default: 90 days (3 months) expressed in minutes
        private const val DEFAULT_DELAYED_REMINDER_OFFSET = 90 * 24 * 60

        /**
         * Starter templates offered in the editor when a studio opens the screen for the
         * first time. Every rule ships disabled, so none of this text reaches a customer
         * until the studio has read it and switched the rule on.
         */
        private const val DEFAULT_PRE_VISIT_TEMPLATE =
            "Przypominamy o wizycie dnia {{data}} o godz. {{godzina}}. Do zobaczenia, {{imie}}!"
        private const val DEFAULT_POST_VISIT_TEMPLATE =
            "Dziękujemy za wizytę, {{imie}}! Mamy nadzieję, że jesteś zadowolony z usługi."
        private const val DEFAULT_DELAYED_REMINDER_TEMPLATE =
            "Cześć {{imie}}! Minęły 3 miesiące od Twojej ostatniej wizyty. Czas na kolejny detailing? Zapraszamy!"
        private const val DEFAULT_BOOKING_CONFIRMATION_TEMPLATE =
            "Drogi/a {{imie}}, potwierdzamy rezerwację na {{data}} o godz. {{godzina}}. Czekamy na Ciebie!"
        private const val DEFAULT_RESCHEDULE_CONFIRMATION_TEMPLATE =
            "Drogi/a {{imie}}, termin Twojej wizyty został zmieniony na {{data}} o godz. {{godzina}}. Do zobaczenia!"
        private const val DEFAULT_VISIT_READY_FOR_PICKUP_TEMPLATE =
            "Drogi/a {{imie}}, Twój pojazd {{pojazd}} {{rejestracja}} jest gotowy do odbioru. Zapraszamy!"
        private const val DEFAULT_VISIT_CARD_LINK_TEMPLATE =
            "Karta Twojej wizyty {{numer_wizyty}} ({{pojazd}} {{rejestracja}}) jest dostępna tutaj: {{link}}"
        private const val DEFAULT_RESERVATION_CARD_LINK_TEMPLATE =
            "Szczegóły Twojej rezerwacji na {{data}} o godz. {{godzina}} znajdziesz tutaj: {{link}}"
        private const val DEFAULT_UPSELL_CONSENT_TEMPLATE =
            "Odpisz TAK, żeby do rezerwacji dodać usługi: {{uslugi}}. Łącznie {{kwota}} PLN brutto."
        private const val DEFAULT_SIGNATURE_REQUEST_TEMPLATE =
            "Dokument „{{dokument}}” czeka na Twój podpis. Otwórz link, zapoznaj się z treścią i podpisz: {{link}}"

        fun defaultFor(studioId: StudioId) = SmsAutomationConfig(
            studioId = studioId,
            preVisit = SmsAutomationRule(
                enabled = false,
                offsetMinutes = DEFAULT_PRE_VISIT_OFFSET,
                messageTemplate = DEFAULT_PRE_VISIT_TEMPLATE
            ),
            postVisit = SmsAutomationRule(
                enabled = false,
                offsetMinutes = DEFAULT_POST_VISIT_OFFSET,
                messageTemplate = DEFAULT_POST_VISIT_TEMPLATE
            ),
            delayedReminder = SmsAutomationRule(
                enabled = false,
                offsetMinutes = DEFAULT_DELAYED_REMINDER_OFFSET,
                messageTemplate = DEFAULT_DELAYED_REMINDER_TEMPLATE
            ),
            bookingConfirmation = SmsNotificationRule(
                enabled = false,
                messageTemplate = DEFAULT_BOOKING_CONFIRMATION_TEMPLATE
            ),
            rescheduleConfirmation = SmsNotificationRule(
                enabled = false,
                messageTemplate = DEFAULT_RESCHEDULE_CONFIRMATION_TEMPLATE
            ),
            visitReadyForPickup = SmsNotificationRule(
                enabled = false,
                messageTemplate = DEFAULT_VISIT_READY_FOR_PICKUP_TEMPLATE
            ),
            visitCardLink = SmsNotificationRule(
                enabled = false,
                messageTemplate = DEFAULT_VISIT_CARD_LINK_TEMPLATE
            ),
            reservationCardLink = SmsNotificationRule(
                enabled = false,
                messageTemplate = DEFAULT_RESERVATION_CARD_LINK_TEMPLATE
            ),
            upsellConsent = SmsNotificationRule(
                enabled = false,
                messageTemplate = DEFAULT_UPSELL_CONSENT_TEMPLATE
            ),
            signatureRequest = SmsNotificationRule(
                enabled = false,
                messageTemplate = DEFAULT_SIGNATURE_REQUEST_TEMPLATE
            )
        )
    }
}
