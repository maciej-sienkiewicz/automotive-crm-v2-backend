package pl.detailing.crm.communication.rehearsal

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.campaigns.application.SmsSegmentCalculator
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.communication.redirect.CommunicationRedirectService
import pl.detailing.crm.communication.template.MessageTemplateKind
import pl.detailing.crm.communication.template.MessageTemplateRenderer
import pl.detailing.crm.communication.template.UnresolvedPlaceholderException
import pl.detailing.crm.email.automation.GetEmailTemplateConfigHandler
import pl.detailing.crm.email.domain.EmailAutomationConfig
import pl.detailing.crm.shared.InsufficientSmsCreditsException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.smscampaigns.automation.GetAutomationConfigHandler
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfig
import java.time.Instant

data class RehearsalDelivery(val success: Boolean, val providerId: String?, val error: String?)

data class RehearsalItem(
    val seq: Int,
    val total: Int,
    val kind: MessageTemplateKind,
    val channel: RehearsalChannel,
    val enabled: Boolean,
    val subject: String?,
    val body: String,
    val segments: Int?,
    val findings: List<Finding>,
    var delivery: RehearsalDelivery? = null
) {
    val hasErrors: Boolean get() = findings.any { it.severity == Severity.ERROR }
    val stamp: String get() = "[R%02d/%d] ".format(seq, total)
}

data class RehearsalReport(
    val studioId: StudioId,
    val generatedAt: Instant,
    val redirectPhone: String?,
    val redirectEmail: String?,
    val items: List<RehearsalItem>,
    val sent: Boolean
) {
    val errorCount: Int get() = items.sumOf { i -> i.findings.count { it.severity == Severity.ERROR } }
    val warningCount: Int get() = items.sumOf { i -> i.findings.count { it.severity == Severity.WARNING } }
    val hasErrors: Boolean get() = errorCount > 0
}

/**
 * Renders every template the studio owns with [RehearsalFixture] and — only when the
 * studio's redirect is on — sends all of them so the studio can read them on its own phone
 * and inbox.
 *
 * All-or-nothing: one ERROR in any message means nothing is sent. A blank template is not
 * an error (the rule would simply not fire) but it is reported, because "enabled and empty"
 * is the most common reason a message never arrives.
 *
 * Kinds without a studio-owned template ([MessageTemplateKind.CAMPAIGN] — the campaign
 * editor has its own "send a test to myself") are not part of the rehearsal.
 */
@Service
class CommsRehearsalRunner(
    private val smsConfig: GetAutomationConfigHandler,
    private val emailConfig: GetEmailTemplateConfigHandler,
    private val renderer: MessageTemplateRenderer,
    private val redirectService: CommunicationRedirectService,
    private val gateway: OutboundCommunicationGateway
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun plan(studioId: StudioId): RehearsalReport {
        val redirect = redirectService.activeFor(studioId.value)
        val sms = smsConfig.handle(studioId)
        val email = emailConfig.handle(studioId)
        val moment = RehearsalFixture.tomorrowTen()

        val smsKinds = MessageTemplateKind.entries.filter { it.name.startsWith("SMS_") }
        val emailKinds = MessageTemplateKind.entries.filter { it.name.startsWith("EMAIL_") }

        val items = smsKinds.mapIndexed { i, kind ->
            val (enabled, template) = sms.templateFor(kind)
            render(kind, RehearsalChannel.SMS, i + 1, smsKinds.size, enabled, null, template, moment)
        } + emailKinds.mapIndexed { i, kind ->
            val (enabled, subject, body) = email.templateFor(kind)
            render(kind, RehearsalChannel.EMAIL, i + 1, emailKinds.size, enabled, subject, body, moment)
        }

        return RehearsalReport(
            studioId = studioId,
            generatedAt = Instant.now(),
            redirectPhone = redirect?.phone,
            redirectEmail = redirect?.email,
            items = items,
            sent = false
        )
    }

    fun run(studioId: StudioId): RehearsalReport {
        redirectService.activeFor(studioId.value)
            ?: throw ValidationException("Włącz najpierw przekierowanie wiadomości na swoje dane — bez niego wysyłka testowa nie ruszy")

        val report = plan(studioId)
        if (report.hasErrors) return report

        val sendable = report.items.filter { it.body.isNotBlank() && (it.channel == RehearsalChannel.SMS || !it.subject.isNullOrBlank()) }
        for (item in sendable) {
            // Re-checked per message: if someone flips the switch off mid-run, the fixture
            // customer's address is unroutable and nothing else goes anywhere.
            if (redirectService.activeFor(studioId.value) == null) {
                item.delivery = RehearsalDelivery(false, null, "Przekierowanie wyłączone w trakcie wysyłki — przerwano")
                break
            }
            item.delivery = try {
                when (item.channel) {
                    RehearsalChannel.SMS -> gateway.sendTransactionalSms(
                        studioId.value, RehearsalFixture.CUSTOMER_PHONE, item.stamp + item.body
                    ).let { RehearsalDelivery(it.success, it.externalMessageId, it.errorMessage) }
                    RehearsalChannel.EMAIL -> gateway.sendTransactionalEmail(
                        studioId.value, RehearsalFixture.CUSTOMER_EMAIL, item.stamp + item.subject.orEmpty(), item.body
                    ).let { RehearsalDelivery(it.success, it.messageId, it.errorMessage) }
                }
            } catch (e: InsufficientSmsCreditsException) {
                RehearsalDelivery(false, null, e.message)
            }
            logger.info("Rehearsal {} {} for studio={}: {}", item.channel, item.kind, studioId.value, item.delivery)
        }
        return report.copy(sent = true)
    }

    private fun render(
        kind: MessageTemplateKind,
        channel: RehearsalChannel,
        seq: Int,
        total: Int,
        enabled: Boolean,
        subjectTemplate: String?,
        bodyTemplate: String,
        moment: Instant
    ): RehearsalItem {
        val values = RehearsalFixture.values(kind, seq, moment)
        val blank = bodyTemplate.isBlank() || (channel == RehearsalChannel.EMAIL && subjectTemplate.isNullOrBlank())
        if (blank) {
            return RehearsalItem(
                seq, total, kind, channel, enabled, subjectTemplate, bodyTemplate, null,
                listOf(Finding(Severity.WARNING, "template-empty", if (enabled) "reguła włączona, ale bez treści — nic nie wyjdzie" else "reguła wyłączona i bez treści"))
            )
        }
        return try {
            val subject = subjectTemplate?.let { renderer.render(it, values) }
            val body = renderer.render(bodyTemplate, values)
            val findings = RenderedMessageValidator.validate(channel, subject, body, values)
            val segments = if (channel == RehearsalChannel.SMS) SmsSegmentCalculator.segments(body) else null
            RehearsalItem(seq, total, kind, channel, enabled, subject, body, segments, findings)
        } catch (e: UnresolvedPlaceholderException) {
            RehearsalItem(
                seq, total, kind, channel, enabled, subjectTemplate, bodyTemplate, null,
                listOf(Finding(Severity.ERROR, "unknown-placeholder", e.placeholders.joinToString(", ") { "{{$it}}" }))
            )
        }
    }

    private fun SmsAutomationConfig.templateFor(kind: MessageTemplateKind): Pair<Boolean, String> = when (kind) {
        MessageTemplateKind.SMS_PRE_VISIT -> preVisit.enabled to preVisit.messageTemplate
        MessageTemplateKind.SMS_POST_VISIT -> postVisit.enabled to postVisit.messageTemplate
        MessageTemplateKind.SMS_DELAYED_REMINDER -> delayedReminder.enabled to delayedReminder.messageTemplate
        MessageTemplateKind.SMS_BOOKING_CONFIRMATION -> bookingConfirmation.enabled to bookingConfirmation.messageTemplate
        MessageTemplateKind.SMS_RESCHEDULE_CONFIRMATION -> rescheduleConfirmation.enabled to rescheduleConfirmation.messageTemplate
        MessageTemplateKind.SMS_VISIT_READY_FOR_PICKUP -> visitReadyForPickup.enabled to visitReadyForPickup.messageTemplate
        MessageTemplateKind.SMS_VISIT_CARD_LINK -> visitCardLink.enabled to visitCardLink.messageTemplate
        MessageTemplateKind.SMS_RESERVATION_CARD_LINK -> reservationCardLink.enabled to reservationCardLink.messageTemplate
        MessageTemplateKind.SMS_UPSELL_CONSENT -> upsellConsent.enabled to upsellConsent.messageTemplate
        MessageTemplateKind.SMS_SIGNATURE_REQUEST -> signatureRequest.enabled to signatureRequest.messageTemplate
        else -> error("$kind is not an SMS template")
    }

    private fun EmailAutomationConfig.templateFor(kind: MessageTemplateKind): Triple<Boolean, String, String> = when (kind) {
        MessageTemplateKind.EMAIL_VISIT_WELCOME -> visitWelcome.let { Triple(it.enabled, it.subjectTemplate, it.bodyTemplate) }
        MessageTemplateKind.EMAIL_VISIT_READY_FOR_PICKUP -> visitReadyForPickup.let { Triple(it.enabled, it.subjectTemplate, it.bodyTemplate) }
        MessageTemplateKind.EMAIL_VISIT_CARD_LINK -> visitCardLink.let { Triple(it.enabled, it.subjectTemplate, it.bodyTemplate) }
        MessageTemplateKind.EMAIL_RESERVATION_CARD_LINK -> reservationCardLink.let { Triple(it.enabled, it.subjectTemplate, it.bodyTemplate) }
        MessageTemplateKind.EMAIL_BATCH_ORDER_CLOSE -> batchOrderClose.let { Triple(it.enabled, it.subjectTemplate, it.bodyTemplate) }
        else -> error("$kind is not an e-mail template")
    }
}
