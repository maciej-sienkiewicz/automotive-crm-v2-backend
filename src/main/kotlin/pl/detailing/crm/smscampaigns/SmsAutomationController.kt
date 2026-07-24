package pl.detailing.crm.smscampaigns

import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.smscampaigns.automation.GetAutomationConfigHandler
import pl.detailing.crm.smscampaigns.automation.UpdateAutomationConfigCommand
import pl.detailing.crm.smscampaigns.automation.UpdateAutomationConfigHandler
import pl.detailing.crm.smscampaigns.automation.UpdateAutomationRuleCommand
import pl.detailing.crm.smscampaigns.automation.UpdateNotificationRuleCommand
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfig
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.role.domain.Permission

// ── Request / Response DTOs ──────────────────────────────────────────────────

data class SmsAutomationRuleDto(
    val enabled: Boolean,
    val offsetMinutes: Int,
    val messageTemplate: String
)

data class SmsNotificationRuleDto(
    val enabled: Boolean,
    val messageTemplate: String
)

data class SmsAutomationConfigDto(
    val preVisit: SmsAutomationRuleDto,
    val postVisit: SmsAutomationRuleDto,
    val bookingConfirmation: SmsNotificationRuleDto,
    val rescheduleConfirmation: SmsNotificationRuleDto,
    val visitReadyForPickup: SmsNotificationRuleDto
)

// ── Mapping ──────────────────────────────────────────────────────────────────

private fun SmsAutomationConfig.toDto() = SmsAutomationConfigDto(
    preVisit = SmsAutomationRuleDto(preVisit.enabled, preVisit.offsetMinutes, preVisit.messageTemplate),
    postVisit = SmsAutomationRuleDto(postVisit.enabled, postVisit.offsetMinutes, postVisit.messageTemplate),
    bookingConfirmation = SmsNotificationRuleDto(bookingConfirmation.enabled, bookingConfirmation.messageTemplate),
    rescheduleConfirmation = SmsNotificationRuleDto(rescheduleConfirmation.enabled, rescheduleConfirmation.messageTemplate),
    visitReadyForPickup = SmsNotificationRuleDto(visitReadyForPickup.enabled, visitReadyForPickup.messageTemplate)
)

// ── Controller ───────────────────────────────────────────────────────────────

/**
 * REST surface for the SMS automation configuration.
 *
 * GET  /api/v1/sms-campaigns/automation  → returns current config for the authenticated studio
 * PUT  /api/v1/sms-campaigns/automation  → replaces the config (OWNER / MANAGER only)
 */
@RequiresPermission(Permission.COMMUNICATION_SEND)
@RestController
@RequestMapping("/api/v1/sms-campaigns/automation")
class SmsAutomationController(
    private val getConfigHandler: GetAutomationConfigHandler,
    private val updateConfigHandler: UpdateAutomationConfigHandler
) {

    @GetMapping
    fun getAutomationConfig(): ResponseEntity<SmsAutomationConfigDto> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val config = getConfigHandler.handle(principal.studioId)

        ResponseEntity.ok(config.toDto())
    }

    @PutMapping
    fun updateAutomationConfig(
        @RequestBody request: SmsAutomationConfigDto
    ): ResponseEntity<SmsAutomationConfigDto> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val existingDelayedReminder = getConfigHandler.handle(principal.studioId).delayedReminder

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
                enabled = existingDelayedReminder.enabled,
                offsetMinutes = existingDelayedReminder.offsetMinutes,
                messageTemplate = existingDelayedReminder.messageTemplate
            ),
            bookingConfirmation = UpdateNotificationRuleCommand(
                enabled = request.bookingConfirmation.enabled,
                messageTemplate = request.bookingConfirmation.messageTemplate
            ),
            rescheduleConfirmation = UpdateNotificationRuleCommand(
                enabled = request.rescheduleConfirmation.enabled,
                messageTemplate = request.rescheduleConfirmation.messageTemplate
            ),
            visitReadyForPickup = UpdateNotificationRuleCommand(
                enabled = request.visitReadyForPickup.enabled,
                messageTemplate = request.visitReadyForPickup.messageTemplate
            )
        )

        val updated = updateConfigHandler.handle(command)

        ResponseEntity.ok(updated.toDto())
    }
}
