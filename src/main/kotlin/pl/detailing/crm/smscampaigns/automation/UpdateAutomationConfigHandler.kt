package pl.detailing.crm.smscampaigns.automation

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfig
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfigRepository
import pl.detailing.crm.smscampaigns.domain.SmsAutomationRule

data class UpdateAutomationConfigCommand(
    val studioId: StudioId,
    val preVisit: UpdateAutomationRuleCommand,
    val postVisit: UpdateAutomationRuleCommand,
    val delayedReminder: UpdateAutomationRuleCommand
)

data class UpdateAutomationRuleCommand(
    val enabled: Boolean,
    val offsetMinutes: Int,
    val messageTemplate: String
)

/**
 * Persists an updated automation config for a studio.
 * Performs an upsert: creates a new row if none exists, updates otherwise.
 */
@Service
class UpdateAutomationConfigHandler(
    private val configRepository: SmsAutomationConfigRepository
) {
    @Transactional
    fun handle(command: UpdateAutomationConfigCommand): SmsAutomationConfig {
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
            )
        )
        return configRepository.save(config)
    }
}
