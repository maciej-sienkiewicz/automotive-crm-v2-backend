package pl.detailing.crm.smscampaigns

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

// ── Request / Response DTOs ──────────────────────────────────────────────────

data class SmsAutomationRuleDto(
    val enabled: Boolean,
    val offsetMinutes: Int,
    val messageTemplate: String
)

data class SmsAutomationConfigDto(
    val preVisit: SmsAutomationRuleDto,
    val postVisit: SmsAutomationRuleDto,
    val delayedReminder: SmsAutomationRuleDto
)

// ── Controller ───────────────────────────────────────────────────────────────

/**
 * REST surface for the SMS automation configuration.
 *
 * GET  /api/v1/sms-campaigns/automation  → returns current config for the authenticated studio
 * PUT  /api/v1/sms-campaigns/automation  → replaces the config (OWNER / MANAGER only)
 */
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

        ResponseEntity.ok(
            SmsAutomationConfigDto(
                preVisit = SmsAutomationRuleDto(
                    enabled = config.preVisit.enabled,
                    offsetMinutes = config.preVisit.offsetMinutes,
                    messageTemplate = config.preVisit.messageTemplate
                ),
                postVisit = SmsAutomationRuleDto(
                    enabled = config.postVisit.enabled,
                    offsetMinutes = config.postVisit.offsetMinutes,
                    messageTemplate = config.postVisit.messageTemplate
                ),
                delayedReminder = SmsAutomationRuleDto(
                    enabled = config.delayedReminder.enabled,
                    offsetMinutes = config.delayedReminder.offsetMinutes,
                    messageTemplate = config.delayedReminder.messageTemplate
                )
            )
        )
    }

    @PutMapping
    fun updateAutomationConfig(
        @RequestBody request: SmsAutomationConfigDto
    ): ResponseEntity<SmsAutomationConfigDto> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can update SMS automation config")
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
            )
        )

        val updated = updateConfigHandler.handle(command)

        ResponseEntity.ok(
            SmsAutomationConfigDto(
                preVisit = SmsAutomationRuleDto(
                    enabled = updated.preVisit.enabled,
                    offsetMinutes = updated.preVisit.offsetMinutes,
                    messageTemplate = updated.preVisit.messageTemplate
                ),
                postVisit = SmsAutomationRuleDto(
                    enabled = updated.postVisit.enabled,
                    offsetMinutes = updated.postVisit.offsetMinutes,
                    messageTemplate = updated.postVisit.messageTemplate
                ),
                delayedReminder = SmsAutomationRuleDto(
                    enabled = updated.delayedReminder.enabled,
                    offsetMinutes = updated.delayedReminder.offsetMinutes,
                    messageTemplate = updated.delayedReminder.messageTemplate
                )
            )
        )
    }
}
