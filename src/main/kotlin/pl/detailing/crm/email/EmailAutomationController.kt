package pl.detailing.crm.email

import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.communication.template.MessageTemplateKind
import pl.detailing.crm.communication.template.MessageTemplateRenderer
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.email.automation.GetEmailTemplateConfigHandler
import pl.detailing.crm.email.automation.UpdateEmailNotificationRuleCommand
import pl.detailing.crm.email.automation.UpdateEmailTemplateConfigCommand
import pl.detailing.crm.email.automation.UpdateEmailTemplateConfigHandler
import pl.detailing.crm.email.domain.EmailAutomationConfig

// ── Request / Response DTOs ──────────────────────────────────────────────────

data class EmailNotificationRuleDto(
    val enabled: Boolean,
    val subjectTemplate: String,
    val bodyTemplate: String
)

data class EmailAutomationConfigDto(
    val visitWelcome: EmailNotificationRuleDto,
    val visitReadyForPickup: EmailNotificationRuleDto,
    val batchOrderClose: EmailNotificationRuleDto,
    val visitCardLink: EmailNotificationRuleDto,
    val reservationCardLink: EmailNotificationRuleDto
)

// ── Mapping ──────────────────────────────────────────────────────────────────

private fun EmailAutomationConfig.toDto() = EmailAutomationConfigDto(
    visitWelcome = visitWelcome.toDto(),
    visitReadyForPickup = visitReadyForPickup.toDto(),
    batchOrderClose = batchOrderClose.toDto(),
    visitCardLink = visitCardLink.toDto(),
    reservationCardLink = reservationCardLink.toDto()
)

private fun pl.detailing.crm.email.domain.EmailNotificationRule.toDto() = EmailNotificationRuleDto(
    enabled = enabled,
    subjectTemplate = subjectTemplate,
    bodyTemplate = bodyTemplate
)

private fun EmailNotificationRuleDto.toCommand() =
    UpdateEmailNotificationRuleCommand(enabled, subjectTemplate, bodyTemplate)

// ── Controller ───────────────────────────────────────────────────────────────

/**
 * REST surface for the email template configuration.
 *
 * GET  /api/v1/email-campaigns/automation  → returns current config for the authenticated studio
 * PUT  /api/v1/email-campaigns/automation  → replaces the config (OWNER / MANAGER only)
 *
 * A template using a placeholder the message cannot fill is rejected here rather than
 * shipped to a customer as literal `{{...}}`.
 */
@RestController
@RequestMapping("/api/v1/email-campaigns/automation")
@RequiresPermission(Permission.COMMUNICATION_SEND)
class EmailAutomationController(
    private val getConfigHandler: GetEmailTemplateConfigHandler,
    private val updateConfigHandler: UpdateEmailTemplateConfigHandler,
    private val renderer: MessageTemplateRenderer
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

        validate(request)

        val command = UpdateEmailTemplateConfigCommand(
            studioId = principal.studioId,
            visitWelcome = request.visitWelcome.toCommand(),
            visitReadyForPickup = request.visitReadyForPickup.toCommand(),
            batchOrderClose = request.batchOrderClose.toCommand(),
            visitCardLink = request.visitCardLink.toCommand(),
            reservationCardLink = request.reservationCardLink.toCommand()
        )

        val updated = updateConfigHandler.handle(command)
        ResponseEntity.ok(updated.toDto())
    }

    private fun validate(request: EmailAutomationConfigDto) {
        fun check(kind: MessageTemplateKind, label: String, rule: EmailNotificationRuleDto) {
            kind.validate(rule.subjectTemplate, renderer, "$label — temat")
            kind.validate(rule.bodyTemplate, renderer, "$label — treść")
        }

        check(MessageTemplateKind.EMAIL_VISIT_WELCOME, "Powitanie przy przyjęciu pojazdu", request.visitWelcome)
        check(MessageTemplateKind.EMAIL_VISIT_READY_FOR_PICKUP, "Pojazd gotowy do odbioru", request.visitReadyForPickup)
        check(MessageTemplateKind.EMAIL_BATCH_ORDER_CLOSE, "Zestawienie zbiorcze", request.batchOrderClose)
        check(MessageTemplateKind.EMAIL_VISIT_CARD_LINK, "Link do Karty Wizyty", request.visitCardLink)
        check(
            MessageTemplateKind.EMAIL_RESERVATION_CARD_LINK,
            "Link do Karty Rezerwacji",
            request.reservationCardLink
        )
    }
}
