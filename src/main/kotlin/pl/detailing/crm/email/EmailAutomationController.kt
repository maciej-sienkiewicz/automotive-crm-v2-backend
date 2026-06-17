package pl.detailing.crm.email

import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.email.automation.GetEmailTemplateConfigHandler
import pl.detailing.crm.email.automation.UpdateEmailNotificationRuleCommand
import pl.detailing.crm.email.automation.UpdateEmailTemplateConfigCommand
import pl.detailing.crm.email.automation.UpdateEmailTemplateConfigHandler
import pl.detailing.crm.email.domain.EmailAutomationConfig
import pl.detailing.crm.shared.ForbiddenException

// ── Request / Response DTOs ──────────────────────────────────────────────────

data class EmailNotificationRuleDto(
    val enabled: Boolean,
    val subjectTemplate: String,
    val bodyTemplate: String
)

data class EmailAutomationConfigDto(
    val visitWelcome: EmailNotificationRuleDto,
    val visitReadyForPickup: EmailNotificationRuleDto
)

// ── Mapping ──────────────────────────────────────────────────────────────────

private fun EmailAutomationConfig.toDto() = EmailAutomationConfigDto(
    visitWelcome = EmailNotificationRuleDto(
        enabled = visitWelcome.enabled,
        subjectTemplate = visitWelcome.subjectTemplate,
        bodyTemplate = visitWelcome.bodyTemplate
    ),
    visitReadyForPickup = EmailNotificationRuleDto(
        enabled = visitReadyForPickup.enabled,
        subjectTemplate = visitReadyForPickup.subjectTemplate,
        bodyTemplate = visitReadyForPickup.bodyTemplate
    )
)

// ── Controller ───────────────────────────────────────────────────────────────

/**
 * REST surface for the email template configuration.
 *
 * GET  /api/v1/email-campaigns/automation  → returns current config for the authenticated studio
 * PUT  /api/v1/email-campaigns/automation  → replaces the config (OWNER / MANAGER only)
 */
@RestController
@RequestMapping("/api/v1/email-campaigns/automation")
class EmailAutomationController(
    private val getConfigHandler: GetEmailTemplateConfigHandler,
    private val updateConfigHandler: UpdateEmailTemplateConfigHandler
) {

    @GetMapping
    fun getAutomationConfig(): ResponseEntity<EmailAutomationConfigDto> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val config = getConfigHandler.handle(principal.studioId)
        ResponseEntity.ok(config.toDto())
    }

    @PutMapping
    fun updateAutomationConfig(
        @RequestBody request: EmailAutomationConfigDto
    ): ResponseEntity<EmailAutomationConfigDto> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (!principal.isOwner) {
            throw ForbiddenException("Only OWNER and MANAGER can update email automation config")
        }

        val command = UpdateEmailTemplateConfigCommand(
            studioId = principal.studioId,
            visitWelcome = UpdateEmailNotificationRuleCommand(
                enabled = request.visitWelcome.enabled,
                subjectTemplate = request.visitWelcome.subjectTemplate,
                bodyTemplate = request.visitWelcome.bodyTemplate
            ),
            visitReadyForPickup = UpdateEmailNotificationRuleCommand(
                enabled = request.visitReadyForPickup.enabled,
                subjectTemplate = request.visitReadyForPickup.subjectTemplate,
                bodyTemplate = request.visitReadyForPickup.bodyTemplate
            )
        )

        val updated = updateConfigHandler.handle(command)
        ResponseEntity.ok(updated.toDto())
    }
}
