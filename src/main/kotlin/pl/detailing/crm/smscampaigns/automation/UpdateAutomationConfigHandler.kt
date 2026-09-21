package pl.detailing.crm.smscampaigns.automation

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.livemetrics.BusinessEventPublisher
import pl.detailing.crm.livemetrics.domain.BusinessEventType
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfig
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfigRepository
import pl.detailing.crm.smscampaigns.domain.SmsAutomationRule
import pl.detailing.crm.smscampaigns.domain.SmsNotificationRule

data class UpdateAutomationConfigCommand(
    val studioId: StudioId,
    val preVisit: UpdateAutomationRuleCommand,
    val postVisit: UpdateAutomationRuleCommand,
    val delayedReminder: UpdateAutomationRuleCommand,
    val bookingConfirmation: UpdateNotificationRuleCommand,
    val rescheduleConfirmation: UpdateNotificationRuleCommand,
    val visitReadyForPickup: UpdateNotificationRuleCommand,
    val visitCardLink: UpdateNotificationRuleCommand,
    val reservationCardLink: UpdateNotificationRuleCommand,
    val upsellConsent: UpdateNotificationRuleCommand,
    val upsellSuggestion: UpdateNotificationRuleCommand,
    val signatureRequest: UpdateNotificationRuleCommand
)

data class UpdateAutomationRuleCommand(
    val enabled: Boolean,
    val offsetMinutes: Int,
    val messageTemplate: String
)

data class UpdateNotificationRuleCommand(
    val enabled: Boolean,
    val messageTemplate: String
)

/**
 * Persists an updated automation config for a studio.
 * Performs an upsert: creates a new row if none exists, updates otherwise.
 */
@Service
class UpdateAutomationConfigHandler(
    private val configRepository: SmsAutomationConfigRepository,
    private val businessEventPublisher: BusinessEventPublisher
) {
    @Transactional
    fun handle(command: UpdateAutomationConfigCommand): SmsAutomationConfig {
        validateOffset("przed wizytą", command.preVisit)
        validateOffset("po wizycie", command.postVisit)
        validateOffset("przypomnienia po usłudze", command.delayedReminder)

        val config = SmsAutomationConfig(
            studioId = command.studioId,
            preVisit = SmsAutomationRule(
                enabled = command.preVisit.enabled,
                offsetMinutes = command.preVisit.offsetMinutes,
                messageTemplate = command.preVisit.messageTemplate
            ),
            postVisit = SmsAutomationRule(
                enabled = command.postVisit.enabled,
                offsetMinutes = command.postVisit.offsetMinutes,
                messageTemplate = command.postVisit.messageTemplate
            ),
            delayedReminder = SmsAutomationRule(
                enabled = command.delayedReminder.enabled,
                offsetMinutes = command.delayedReminder.offsetMinutes,
                messageTemplate = command.delayedReminder.messageTemplate
            ),
            bookingConfirmation = SmsNotificationRule(
                enabled = command.bookingConfirmation.enabled,
                messageTemplate = command.bookingConfirmation.messageTemplate
            ),
            rescheduleConfirmation = SmsNotificationRule(
                enabled = command.rescheduleConfirmation.enabled,
                messageTemplate = command.rescheduleConfirmation.messageTemplate
            ),
            visitReadyForPickup = SmsNotificationRule(
                enabled = command.visitReadyForPickup.enabled,
                messageTemplate = command.visitReadyForPickup.messageTemplate
            ),
            visitCardLink = SmsNotificationRule(
                enabled = command.visitCardLink.enabled,
                messageTemplate = command.visitCardLink.messageTemplate
            ),
            reservationCardLink = SmsNotificationRule(
                enabled = command.reservationCardLink.enabled,
                messageTemplate = command.reservationCardLink.messageTemplate
            ),
            upsellConsent = SmsNotificationRule(
                enabled = command.upsellConsent.enabled,
                messageTemplate = command.upsellConsent.messageTemplate
            ),
            upsellSuggestion = SmsNotificationRule(
                enabled = command.upsellSuggestion.enabled,
                messageTemplate = command.upsellSuggestion.messageTemplate
            ),
            signatureRequest = SmsNotificationRule(
                enabled = command.signatureRequest.enabled,
                messageTemplate = command.signatureRequest.messageTemplate
            )
        )
        val saved = configRepository.save(config)

        // Live metrics — liczymy zapis konfiguracji automatów/szablonów SMS.
        businessEventPublisher.publish(
            tenantId = command.studioId,
            type = BusinessEventType.SMS_TEMPLATE_UPDATED,
            attributes = mapOf(
                "enabledRules" to listOf(
                    command.preVisit.enabled,
                    command.postVisit.enabled,
                    command.delayedReminder.enabled,
                    command.bookingConfirmation.enabled,
                    command.rescheduleConfirmation.enabled,
                    command.visitReadyForPickup.enabled,
                    command.visitCardLink.enabled,
                    command.reservationCardLink.enabled,
                    command.upsellConsent.enabled,
                    command.upsellSuggestion.enabled,
                    command.signatureRequest.enabled
                ).count { it }.toString()
            )
        )

        return saved
    }

    /**
     * Offset liczy się od zdarzenia i musi być dodatni. Zero albo wartość ujemna dla reguły
     * „przed wizytą" oznaczałyby przypomnienie wysłane w trakcie albo po wizycie — automat
     * ma na to osobną blokadę, ale zła konfiguracja ma nie przejść już tutaj. Reguła
     * wyłączona nie jest sprawdzana: studio może mieć w niej cokolwiek, dopóki jej nie włączy.
     */
    private fun validateOffset(label: String, rule: UpdateAutomationRuleCommand) {
        if (rule.enabled && rule.offsetMinutes < 1) {
            throw ValidationException("Wyprzedzenie reguły $label musi wynosić co najmniej 1 minutę")
        }
    }
}
