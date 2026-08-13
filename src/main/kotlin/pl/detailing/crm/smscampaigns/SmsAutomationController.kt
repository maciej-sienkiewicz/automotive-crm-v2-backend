package pl.detailing.crm.smscampaigns

import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.communication.template.MessageTemplateKind
import pl.detailing.crm.communication.template.MessageTemplateRenderer
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
    val delayedReminder: SmsAutomationRuleDto,
    val bookingConfirmation: SmsNotificationRuleDto,
    val rescheduleConfirmation: SmsNotificationRuleDto,
    val visitReadyForPickup: SmsNotificationRuleDto,
    val visitCardLink: SmsNotificationRuleDto,
    val reservationCardLink: SmsNotificationRuleDto,
    val upsellConsent: SmsNotificationRuleDto,
    val signatureRequest: SmsNotificationRuleDto
)

// ── Mapping ──────────────────────────────────────────────────────────────────

private fun SmsAutomationConfig.toDto() = SmsAutomationConfigDto(
    preVisit = SmsAutomationRuleDto(preVisit.enabled, preVisit.offsetMinutes, preVisit.messageTemplate),
    postVisit = SmsAutomationRuleDto(postVisit.enabled, postVisit.offsetMinutes, postVisit.messageTemplate),
    delayedReminder = SmsAutomationRuleDto(
        delayedReminder.enabled, delayedReminder.offsetMinutes, delayedReminder.messageTemplate
    ),
    bookingConfirmation = SmsNotificationRuleDto(bookingConfirmation.enabled, bookingConfirmation.messageTemplate),
    rescheduleConfirmation = SmsNotificationRuleDto(
        rescheduleConfirmation.enabled, rescheduleConfirmation.messageTemplate
    ),
    visitReadyForPickup = SmsNotificationRuleDto(visitReadyForPickup.enabled, visitReadyForPickup.messageTemplate),
    visitCardLink = SmsNotificationRuleDto(visitCardLink.enabled, visitCardLink.messageTemplate),
    reservationCardLink = SmsNotificationRuleDto(reservationCardLink.enabled, reservationCardLink.messageTemplate),
    upsellConsent = SmsNotificationRuleDto(upsellConsent.enabled, upsellConsent.messageTemplate),
    signatureRequest = SmsNotificationRuleDto(signatureRequest.enabled, signatureRequest.messageTemplate)
)

// ── Controller ───────────────────────────────────────────────────────────────

/**
 * REST surface for the SMS automation configuration.
 *
 * GET  /api/v1/sms-campaigns/automation  → returns current config for the authenticated studio
 * PUT  /api/v1/sms-campaigns/automation  → replaces the config (OWNER / MANAGER only)
 *
 * Every rule the studio can see is a rule the studio can change — no field is silently
 * preserved from the previous state, and a template using a placeholder the message
 * cannot fill is rejected here rather than shipped to a customer as literal `{{...}}`.
 */
@RequiresPermission(Permission.COMMUNICATION_SEND)
@RestController
@RequestMapping("/api/v1/sms-campaigns/automation")
class SmsAutomationController(
    private val getConfigHandler: GetAutomationConfigHandler,
    private val updateConfigHandler: UpdateAutomationConfigHandler,
    private val renderer: MessageTemplateRenderer
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

        validate(request)

        val command = UpdateAutomationConfigCommand(
            studioId = principal.studioId,
            preVisit = request.preVisit.toCommand(),
            postVisit = request.postVisit.toCommand(),
            delayedReminder = request.delayedReminder.toCommand(),
            bookingConfirmation = request.bookingConfirmation.toCommand(),
            rescheduleConfirmation = request.rescheduleConfirmation.toCommand(),
            visitReadyForPickup = request.visitReadyForPickup.toCommand(),
            visitCardLink = request.visitCardLink.toCommand(),
            reservationCardLink = request.reservationCardLink.toCommand(),
            upsellConsent = request.upsellConsent.toCommand(),
            signatureRequest = request.signatureRequest.toCommand()
        )

        val updated = updateConfigHandler.handle(command)

        ResponseEntity.ok(updated.toDto())
    }

    private fun validate(request: SmsAutomationConfigDto) {
        fun check(kind: MessageTemplateKind, label: String, template: String) =
            kind.validate(template, renderer, label)

        check(MessageTemplateKind.SMS_PRE_VISIT, "Przypomnienie przed wizytą", request.preVisit.messageTemplate)
        check(MessageTemplateKind.SMS_POST_VISIT, "Wiadomość po wizycie", request.postVisit.messageTemplate)
        check(
            MessageTemplateKind.SMS_DELAYED_REMINDER,
            "Przypomnienie po dłuższej przerwie",
            request.delayedReminder.messageTemplate
        )
        check(
            MessageTemplateKind.SMS_BOOKING_CONFIRMATION,
            "Potwierdzenie rezerwacji",
            request.bookingConfirmation.messageTemplate
        )
        check(
            MessageTemplateKind.SMS_RESCHEDULE_CONFIRMATION,
            "Potwierdzenie zmiany terminu",
            request.rescheduleConfirmation.messageTemplate
        )
        check(
            MessageTemplateKind.SMS_VISIT_READY_FOR_PICKUP,
            "Pojazd gotowy do odbioru",
            request.visitReadyForPickup.messageTemplate
        )
        check(MessageTemplateKind.SMS_VISIT_CARD_LINK, "Link do Karty Wizyty", request.visitCardLink.messageTemplate)
        check(
            MessageTemplateKind.SMS_RESERVATION_CARD_LINK,
            "Link do Karty Rezerwacji",
            request.reservationCardLink.messageTemplate
        )
        check(MessageTemplateKind.SMS_UPSELL_CONSENT, "Zgoda na dodanie usług", request.upsellConsent.messageTemplate)
        check(
            MessageTemplateKind.SMS_SIGNATURE_REQUEST,
            "Link do podpisu dokumentu",
            request.signatureRequest.messageTemplate
        )
    }
}

private fun SmsAutomationRuleDto.toCommand() =
    UpdateAutomationRuleCommand(enabled, offsetMinutes, messageTemplate)

private fun SmsNotificationRuleDto.toCommand() =
    UpdateNotificationRuleCommand(enabled, messageTemplate)
