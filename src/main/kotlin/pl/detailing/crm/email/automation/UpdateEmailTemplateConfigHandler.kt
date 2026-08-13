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
    val visitReadyForPickup: UpdateEmailNotificationRuleCommand,
    val batchOrderClose: UpdateEmailNotificationRuleCommand,
    val visitCardLink: UpdateEmailNotificationRuleCommand,
    val reservationCardLink: UpdateEmailNotificationRuleCommand
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
            ),
            batchOrderClose = EmailNotificationRule(
                enabled = command.batchOrderClose.enabled,
                subjectTemplate = command.batchOrderClose.subjectTemplate,
                bodyTemplate = command.batchOrderClose.bodyTemplate
            ),
            visitCardLink = EmailNotificationRule(
                enabled = command.visitCardLink.enabled,
                subjectTemplate = command.visitCardLink.subjectTemplate,
                bodyTemplate = command.visitCardLink.bodyTemplate
            ),
            reservationCardLink = EmailNotificationRule(
                enabled = command.reservationCardLink.enabled,
                subjectTemplate = command.reservationCardLink.subjectTemplate,
                bodyTemplate = command.reservationCardLink.bodyTemplate
            )
        )
        return configRepository.save(config)
    }
}
