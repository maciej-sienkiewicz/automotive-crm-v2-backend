package pl.detailing.crm.studio.settings

import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.UserRole
import pl.detailing.crm.smscampaigns.automation.GetAutomationConfigHandler
import pl.detailing.crm.smscampaigns.automation.UpdateAutomationConfigCommand
import pl.detailing.crm.smscampaigns.automation.UpdateAutomationConfigHandler
import pl.detailing.crm.smscampaigns.automation.UpdateAutomationRuleCommand
import pl.detailing.crm.smscampaigns.automation.UpdateNotificationRuleCommand
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfig

// ── DTOs ─────────────────────────────────────────────────────────────────────

data class SmsTimedRuleDto(
    val enabled: Boolean,
    val offsetMinutes: Int,
    val messageTemplate: String
)

data class SmsEventRuleDto(
    val enabled: Boolean,
    val messageTemplate: String
)

data class SmsTemplateVariableDto(
    val placeholder: String,
    val description: String
)

data class SmsTemplatesResponse(
    val preVisit: SmsTimedRuleDto,
    val postVisit: SmsTimedRuleDto,
    val delayedReminder: SmsTimedRuleDto,
    val bookingConfirmation: SmsEventRuleDto,
    val rescheduleConfirmation: SmsEventRuleDto,
    val availableVariables: List<SmsTemplateVariableDto>
)

data class UpdateSmsTemplatesRequest(
    val preVisit: SmsTimedRuleDto,
    val postVisit: SmsTimedRuleDto,
    val delayedReminder: SmsTimedRuleDto,
    val bookingConfirmation: SmsEventRuleDto,
    val rescheduleConfirmation: SmsEventRuleDto
)

// ── Available template variables ─────────────────────────────────────────────

private val AVAILABLE_VARIABLES = listOf(
    SmsTemplateVariableDto("{{imie}}", "Imię klienta"),
    SmsTemplateVariableDto("{{data}}", "Data wizyty (dd.MM.yyyy)"),
    SmsTemplateVariableDto("{{godzina}}", "Godzina wizyty (HH:mm)"),
    SmsTemplateVariableDto("{{studio}}", "Nazwa studia")
)

// ── Mapping ───────────────────────────────────────────────────────────────────

private fun SmsAutomationConfig.toTemplatesResponse() = SmsTemplatesResponse(
    preVisit = SmsTimedRuleDto(preVisit.enabled, preVisit.offsetMinutes, preVisit.messageTemplate),
    postVisit = SmsTimedRuleDto(postVisit.enabled, postVisit.offsetMinutes, postVisit.messageTemplate),
    delayedReminder = SmsTimedRuleDto(delayedReminder.enabled, delayedReminder.offsetMinutes, delayedReminder.messageTemplate),
    bookingConfirmation = SmsEventRuleDto(bookingConfirmation.enabled, bookingConfirmation.messageTemplate),
    rescheduleConfirmation = SmsEventRuleDto(rescheduleConfirmation.enabled, rescheduleConfirmation.messageTemplate),
    availableVariables = AVAILABLE_VARIABLES
)

// ── Controller ────────────────────────────────────────────────────────────────

/**
 * Settings surface for SMS message templates and automation rules.
 * Supersedes /api/v1/sms-campaigns/automation by exposing all five rules
 * (including delayedReminder) together with template-variable metadata.
 *
 * GET  /api/v1/settings/sms-templates  → current config for authenticated studio
 * PUT  /api/v1/settings/sms-templates  → replace config (OWNER / MANAGER only)
 */
@RestController
@RequestMapping("/api/v1/settings/sms-templates")
class SmsTemplatesController(
    private val getConfigHandler: GetAutomationConfigHandler,
    private val updateConfigHandler: UpdateAutomationConfigHandler
) {

    @GetMapping
    fun getSmsTemplates(): ResponseEntity<SmsTemplatesResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val config = getConfigHandler.handle(principal.studioId)
        ResponseEntity.ok(config.toTemplatesResponse())
    }

    @PutMapping
    fun updateSmsTemplates(
        @RequestBody request: UpdateSmsTemplatesRequest
    ): ResponseEntity<SmsTemplatesResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can update SMS templates")
        }

        val command = UpdateAutomationConfigCommand(
            studioId = principal.studioId,
            preVisit = UpdateAutomationRuleCommand(
                enabled = request.preVisit.enabled,
                offsetMinutes = request.preVisit.offsetMinutes,
                messageTemplate = request.preVisit.messageTemplate
            ),
            postVisit = UpdateAutomationRuleCommand(
                enabled = request.postVisit.enabled,
                offsetMinutes = request.postVisit.offsetMinutes,
                messageTemplate = request.postVisit.messageTemplate
            ),
            delayedReminder = UpdateAutomationRuleCommand(
                enabled = request.delayedReminder.enabled,
                offsetMinutes = request.delayedReminder.offsetMinutes,
                messageTemplate = request.delayedReminder.messageTemplate
            ),
            bookingConfirmation = UpdateNotificationRuleCommand(
                enabled = request.bookingConfirmation.enabled,
                messageTemplate = request.bookingConfirmation.messageTemplate
            ),
            rescheduleConfirmation = UpdateNotificationRuleCommand(
                enabled = request.rescheduleConfirmation.enabled,
                messageTemplate = request.rescheduleConfirmation.messageTemplate
            )
        )

        val updated = updateConfigHandler.handle(command)
        ResponseEntity.ok(updated.toTemplatesResponse())
    }
}
