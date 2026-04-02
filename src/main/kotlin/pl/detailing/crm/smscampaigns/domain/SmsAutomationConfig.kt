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
 * Per-studio configuration for automated SMS sending.
 * One instance exists per studio; missing row means both rules are disabled.
 */
data class SmsAutomationConfig(
    val studioId: StudioId,
    val preVisit: SmsAutomationRule,
    val postVisit: SmsAutomationRule
) {
    companion object {
        private const val DEFAULT_PRE_VISIT_OFFSET = 60
        private const val DEFAULT_POST_VISIT_OFFSET = 30
        private const val DEFAULT_PRE_VISIT_TEMPLATE =
            "Przypominamy o wizycie w {{studio}} dnia {{data}} o godz. {{godzina}}. Do zobaczenia, {{imie}}!"
        private const val DEFAULT_POST_VISIT_TEMPLATE =
            "Dziękujemy za wizytę w {{studio}}, {{imie}}! Mamy nadzieję, że jesteś zadowolony z usługi."

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
            )
        )
    }
}
