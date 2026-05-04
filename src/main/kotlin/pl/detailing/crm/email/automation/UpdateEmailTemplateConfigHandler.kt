package pl.detailing.crm.email.automation

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.email.domain.EmailAutomationConfig
import pl.detailing.crm.email.domain.EmailAutomationConfigRepository
import pl.detailing.crm.email.domain.EmailNotificationRule
import pl.detailing.crm.shared.StudioId

data class UpdateEmailTemplateConfigCommand(
    val studioId: StudioId,
    val visitWelcome: UpdateEmailNotificationRuleCommand,
    val visitReadyForPickup: UpdateEmailNotificationRuleCommand
)

data class UpdateEmailNotificationRuleCommand(
    val enabled: Boolean,
    val subjectTemplate: String,
    val bodyTemplate: String
)

@Service
class UpdateEmailTemplateConfigHandler(
    private val configRepository: EmailAutomationConfigRepository
) {
    @Transactional
    fun handle(command: UpdateEmailTemplateConfigCommand): EmailAutomationConfig {
        val config = EmailAutomationConfig(
            studioId = command.studioId,
            visitWelcome = EmailNotificationRule(
                enabled = command.visitWelcome.enabled,
                subjectTemplate = command.visitWelcome.subjectTemplate,
                bodyTemplate = command.visitWelcome.bodyTemplate
            ),
            visitReadyForPickup = EmailNotificationRule(
                enabled = command.visitReadyForPickup.enabled,
                subjectTemplate = command.visitReadyForPickup.subjectTemplate,
                bodyTemplate = command.visitReadyForPickup.bodyTemplate
            )
        )
        return configRepository.save(config)
    }
}
