package pl.detailing.crm.smscampaigns.domain

import pl.detailing.crm.shared.StudioId

/**
 * A single automation rule controlling when and what SMS to send.
 *
 * [offsetMinutes] is interpreted as:
 *   - PRE_VISIT:  minutes *before* appointment start
 *   - POST_VISIT: minutes *after* appointment end
 */
data class SmsAutomationRule(
    val enabled: Boolean,
    val offsetMinutes: Int,
    val messageTemplate: String
)

/**
 * A single event-triggered notification rule (no time offset — fired immediately on event).
 *   - BOOKING_CONFIRMATION:    sent when a new appointment is created
 *   - RESCHEDULE_CONFIRMATION: sent when an existing appointment is rescheduled
 */
data class SmsNotificationRule(
    val enabled: Boolean,
    val messageTemplate: String
)

/**
 * Per-studio configuration for automated SMS sending.
 * One instance exists per studio; missing row means all rules are disabled.
 */
data class SmsAutomationConfig(
    val studioId: StudioId,
    val preVisit: SmsAutomationRule,
    val postVisit: SmsAutomationRule,
    val delayedReminder: SmsAutomationRule,
    val bookingConfirmation: SmsNotificationRule,
    val rescheduleConfirmation: SmsNotificationRule,
    val visitReadyForPickup: SmsNotificationRule
) {
    companion object {
        private const val DEFAULT_PRE_VISIT_OFFSET = 60
        private const val DEFAULT_POST_VISIT_OFFSET = 30
        // Default: 90 days (3 months) expressed in minutes
        private const val DEFAULT_DELAYED_REMINDER_OFFSET = 90 * 24 * 60
        private const val DEFAULT_PRE_VISIT_TEMPLATE =
            "Przypominamy o wizycie w {{studio}} dnia {{data}} o godz. {{godzina}}. Do zobaczenia, {{imie}}!"
        private const val DEFAULT_POST_VISIT_TEMPLATE =
            "Dziękujemy za wizytę w {{studio}}, {{imie}}! Mamy nadzieję, że jesteś zadowolony z usługi."
        private const val DEFAULT_DELAYED_REMINDER_TEMPLATE =
            "Cześć {{imie}}! Minęły 3 miesiące od Twojej wizyty w {{studio}}. Czas na kolejny detailing? Zapraszamy!"
        private const val DEFAULT_BOOKING_CONFIRMATION_TEMPLATE =
            "Drogi/a {{imie}}, potwierdzamy rezerwację w {{studio}} na {{data}} o godz. {{godzina}}. Czekamy na Ciebie!"
        private const val DEFAULT_RESCHEDULE_CONFIRMATION_TEMPLATE =
            "Drogi/a {{imie}}, termin Twojej wizyty w {{studio}} został zmieniony na {{data}} o godz. {{godzina}}. Do zobaczenia!"
        private const val DEFAULT_VISIT_READY_FOR_PICKUP_TEMPLATE =
            "Drogi/a {{imie}}, Twój pojazd jest gotowy do odbioru w {{studio}}. Zapraszamy!"

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
                enabled = true,
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
            )
        )
    }
}
