package pl.detailing.crm.campaigns.application

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.campaigns.domain.*
import pl.detailing.crm.campaigns.infrastructure.AudienceQueryService
import pl.detailing.crm.campaigns.infrastructure.AudienceRow
import pl.detailing.crm.communication.CommunicationLogService
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.communication.RecordCommunicationCommand
import pl.detailing.crm.shared.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

private val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")

/**
 * Shifts [instant] out of the studio's quiet hours: a send that would land inside
 * the window is moved to the next quiet-hours end. Window may span midnight.
 */
fun shiftOutOfQuietHours(instant: Instant, settings: CampaignSettings): Instant {
    val zoned = instant.atZone(WARSAW)
    val t: LocalTime = zoned.toLocalTime()
    val start = settings.quietHoursStart
    val end = settings.quietHoursEnd
    if (start == end) return instant

    val inQuiet = if (start < end) t >= start && t < end else t >= start || t < end
    if (!inQuiet) return instant

    val endToday = zoned.toLocalDate().atTime(end).atZone(WARSAW)
    return if (endToday.toInstant().isAfter(instant)) endToday.toInstant()
    else endToday.plusDays(1).toInstant()
}

/**
 * Turns due SCHEDULED one-time campaigns into recipient rows (audience is
 * recomputed here — the design's "przeliczamy w dniu wysyłki" decision) and
 * flips them to SENDING. Dispatch itself happens in [CampaignDispatcher].
 */
private fun skipReasonMessage(status: RecipientStatus): String = when (status) {
    RecipientStatus.SKIPPED_NO_CONSENT -> "Brak zgody marketingowej"
    RecipientStatus.SKIPPED_NO_ADDRESS -> "Brak adresu (telefon / e-mail)"
    RecipientStatus.SKIPPED_OPTED_OUT -> "Klient wypisany z komunikacji marketingowej"
    RecipientStatus.SKIPPED_FREQUENCY_CAP -> "Przekroczono limit częstotliwości wysyłki"
    RecipientStatus.SKIPPED_NO_CREDITS -> "Brak kredytów SMS"
    RecipientStatus.STOPPED -> "Wysyłka kampanii zatrzymana"
    else -> "Pominięto"
}

private fun CampaignRecipient.toCommChannel() =
    if (channel == RecipientChannel.SMS) CommunicationChannel.SMS else CommunicationChannel.EMAIL

private fun CampaignRecipient.toCommMessageType() =
    if (channel == RecipientChannel.SMS) CommunicationMessageType.CAMPAIGN_SMS else CommunicationMessageType.CAMPAIGN_EMAIL

@Service
class CampaignMaterializer(
    private val campaigns: CampaignRepository,
    private val recipients: CampaignRecipientRepository,
    private val service: CampaignService,
    private val audienceQuery: AudienceQueryService,
    private val renderer: CampaignTemplateRenderer,
    private val communicationLog: CommunicationLogService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "30 * * * * *")
    @Transactional
    fun materializeDue() {
        val due = campaigns.findDueScheduledForUpdate(Instant.now())
        due.forEach { campaign ->
            runCatching { materialize(campaign) }
                .onFailure { ex ->
                    logger.error("Campaign materialization failed | campaign={}", campaign.id, ex)
                    campaigns.save(campaign.copy(status = CampaignStatus.FAILED, updatedAt = Instant.now()))
                }
        }
    }

    private fun materialize(campaign: Campaign) {
        val settings = service.getSettings(campaign.studioId)
        val now = Instant.now()
        val sendAt = shiftOutOfQuietHours(maxOf(now, campaign.scheduledAt ?: now), settings)

        val rows = mutableListOf<CampaignRecipient>()
        for (channel in campaign.channels()) {
            val audience = audienceQuery.materialize(
                studioId = campaign.studioId,
                criteria = campaign.audience,
                channel = channel,
                frequencyCapDays = settings.frequencyCapDays
            )
            audience.forEach { row ->
                rows += CampaignRecipientFactory.build(
                    campaign, channel, row, sendAt, settings, renderer, triggerVisitId = null
                )
            }
        }

        recipients.saveAll(rows)

        // Log non-PENDING recipients to the customer communication timeline immediately —
        // they will never be picked up by the dispatcher, so this is the only chance.
        rows.filter { it.status != RecipientStatus.PENDING }.forEach { r ->
            communicationLog.record(
                RecordCommunicationCommand(
                    studioId = StudioId(r.studioId),
                    customerId = CustomerId(r.customerId),
                    visitId = null,
                    channel = r.toCommChannel(),
                    messageType = r.toCommMessageType(),
                    recipientAddress = r.address,
                    subject = r.renderedSubject,
                    bodyContent = r.renderedBody,
                    success = false,
                    errorMessage = skipReasonMessage(r.status)
                )
            )
        }

        val skipped = rows.count { it.status != RecipientStatus.PENDING }
        campaigns.save(
            campaign.copy(
                status = CampaignStatus.SENDING,
                startedAt = now,
                recipientsTotal = rows.size,
                recipientsSkipped = skipped,
                updatedAt = now
            )
        )
        logger.info(
            "Campaign materialized | campaign={} total={} pending={} skipped={}",
            campaign.id, rows.size, rows.size - skipped, skipped
        )
    }

}

/** Builds a recipient row from an audience row — shared by materializer and enroller. */
object CampaignRecipientFactory {

    fun build(
        campaign: Campaign,
        channel: RecipientChannel,
        row: AudienceRow,
        sendAt: Instant,
        settings: CampaignSettings,
        renderer: CampaignTemplateRenderer,
        triggerVisitId: UUID?
    ): CampaignRecipient {
        val address = if (channel == RecipientChannel.SMS) row.phone.orEmpty() else row.email.orEmpty()
        val status = when (row.eligibility) {
            AudienceRow.Eligibility.ELIGIBLE -> RecipientStatus.PENDING
            AudienceRow.Eligibility.OPTED_OUT -> RecipientStatus.SKIPPED_OPTED_OUT
            AudienceRow.Eligibility.NO_CONSENT -> RecipientStatus.SKIPPED_NO_CONSENT
            AudienceRow.Eligibility.NO_ADDRESS -> RecipientStatus.SKIPPED_NO_ADDRESS
            AudienceRow.Eligibility.FREQUENCY_CAP -> RecipientStatus.SKIPPED_FREQUENCY_CAP
        }
        val body = when (channel) {
            RecipientChannel.SMS -> {
                val rendered = renderer.render(campaign.smsTemplate.orEmpty(), row)
                val footer = settings.smsFooter?.takeIf { it.isNotBlank() }
                if (footer != null) "$rendered $footer" else rendered
            }
            RecipientChannel.EMAIL -> {
                val rendered = renderer.render(campaign.emailBody.orEmpty(), row)
                val footer = settings.emailFooter?.takeIf { it.isNotBlank() }
                if (footer != null) "$rendered\n\n$footer" else rendered
            }
        }
        val subject = if (channel == RecipientChannel.EMAIL) {
            renderer.render(campaign.emailSubject.orEmpty(), row)
        } else null

        return CampaignRecipient(
            id = UUID.randomUUID(),
            campaignId = campaign.id,
            studioId = campaign.studioId.value,
            customerId = row.customerId,
            channel = channel,
            address = address.ifBlank { "—" },
            renderedSubject = subject,
            renderedBody = body,
            status = status,
            triggerSourceVisitId = triggerVisitId,
            scheduledFor = sendAt,
            createdAt = Instant.now()
        )
    }
}

private fun Campaign.channels(): List<RecipientChannel> = when (channel) {
    CampaignChannel.SMS -> listOf(RecipientChannel.SMS)
    CampaignChannel.EMAIL -> listOf(RecipientChannel.EMAIL)
    CampaignChannel.BOTH -> listOf(RecipientChannel.SMS, RecipientChannel.EMAIL)
}

/**
 * Every minute: sends due PENDING recipients through [OutboundCommunicationGateway]
 * (which re-checks marketing consent and debits SMS credits), records each attempt
 * in the communication log and refreshes campaign counters. A one-time campaign
 * with no pending recipients left is marked COMPLETED.
 */
@Service
class CampaignDispatcher(
    private val campaigns: CampaignRepository,
    private val recipients: CampaignRecipientRepository,
    private val gateway: OutboundCommunicationGateway,
    private val communicationLog: CommunicationLogService,
    private val optOuts: CampaignOptOutRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val BATCH_SIZE = 50
    }

    @Scheduled(cron = "0 * * * * *")
    @Transactional
    fun dispatch() {
        val due = recipients.findDueForDispatch(Instant.now(), BATCH_SIZE)
        if (due.isEmpty()) {
            refreshSendingCampaigns()
            return
        }

        logger.info("Dispatching {} campaign recipient(s)", due.size)
        due.forEach { recipient ->
            runCatching { dispatchOne(recipient) }
                .onFailure { ex ->
                    logger.error("Campaign dispatch error | recipient={}", recipient.id, ex)
                    recipients.save(
                        recipient.copy(status = RecipientStatus.FAILED, errorMessage = ex.message, sentAt = Instant.now())
                    )
                    communicationLog.record(
                        RecordCommunicationCommand(
                            studioId = StudioId(recipient.studioId),
                            customerId = CustomerId(recipient.customerId),
                            visitId = null,
                            channel = recipient.toCommChannel(),
                            messageType = recipient.toCommMessageType(),
                            recipientAddress = recipient.address,
                            subject = recipient.renderedSubject,
                            bodyContent = recipient.renderedBody,
                            success = false,
                            errorMessage = ex.message
                        )
                    )
                }
        }
        refreshSendingCampaigns()
    }

    private fun dispatchOne(recipient: CampaignRecipient) {
        // Opt-out mógł przyjść po zaplanowaniu — ostatnia linia obrony.
        if (optOuts.isOptedOut(recipient.studioId, recipient.customerId)) {
            recipients.save(recipient.copy(status = RecipientStatus.SKIPPED_OPTED_OUT, sentAt = Instant.now()))
            communicationLog.record(
                RecordCommunicationCommand(
                    studioId = StudioId(recipient.studioId),
                    customerId = CustomerId(recipient.customerId),
                    visitId = null,
                    channel = recipient.toCommChannel(),
                    messageType = recipient.toCommMessageType(),
                    recipientAddress = recipient.address,
                    subject = recipient.renderedSubject,
                    bodyContent = recipient.renderedBody,
                    success = false,
                    errorMessage = skipReasonMessage(RecipientStatus.SKIPPED_OPTED_OUT)
                )
            )
            return
        }

        val result: Pair<Boolean, String?> = when (recipient.channel) {
            RecipientChannel.SMS -> {
                try {
                    val r = gateway.sendSms(
                        customerId = recipient.customerId,
                        studioId = recipient.studioId,
                        phoneNumber = recipient.address,
                        message = recipient.renderedBody,
                        context = "Campaign=${recipient.campaignId}"
                    )
                    r.success to r.errorMessage
                } catch (ex: InsufficientSmsCreditsException) {
                    recipients.save(
                        recipient.copy(
                            status = RecipientStatus.SKIPPED_NO_CREDITS,
                            errorMessage = ex.message,
                            sentAt = Instant.now()
                        )
                    )
                    communicationLog.record(
                        RecordCommunicationCommand(
                            studioId = StudioId(recipient.studioId),
                            customerId = CustomerId(recipient.customerId),
                            visitId = null,
                            channel = recipient.toCommChannel(),
                            messageType = recipient.toCommMessageType(),
                            recipientAddress = recipient.address,
                            subject = recipient.renderedSubject,
                            bodyContent = recipient.renderedBody,
                            success = false,
                            errorMessage = skipReasonMessage(RecipientStatus.SKIPPED_NO_CREDITS)
                        )
                    )
                    return
                }
            }
            RecipientChannel.EMAIL -> {
                val r = gateway.sendEmail(
                    customerId = recipient.customerId,
                    studioId = recipient.studioId,
                    to = recipient.address,
                    subject = recipient.renderedSubject.orEmpty(),
                    bodyText = recipient.renderedBody,
                    context = "Campaign=${recipient.campaignId}"
                )
                r.success to r.errorMessage
            }
        }

        val (success, error) = result
        recipients.save(
            recipient.copy(
                status = if (success) RecipientStatus.SENT else RecipientStatus.FAILED,
                errorMessage = error,
                sentAt = Instant.now()
            )
        )

        communicationLog.record(
            RecordCommunicationCommand(
                studioId = StudioId(recipient.studioId),
                customerId = CustomerId(recipient.customerId),
                visitId = null,
                channel = if (recipient.channel == RecipientChannel.SMS) CommunicationChannel.SMS else CommunicationChannel.EMAIL,
                messageType = if (recipient.channel == RecipientChannel.SMS)
                    CommunicationMessageType.CAMPAIGN_SMS else CommunicationMessageType.CAMPAIGN_EMAIL,
                recipientAddress = recipient.address,
                subject = recipient.renderedSubject,
                bodyContent = recipient.renderedBody,
                success = success,
                errorMessage = error
            )
        )
    }

    /** Aktualizuje liczniki kampanii w wysyłce; domyka jednorazowe bez oczekujących odbiorców. */
    private fun refreshSendingCampaigns() {
        val toRefresh = campaigns.findAllByStatus(CampaignStatus.SENDING) +
            campaigns.findAllActiveAutomatic()

        toRefresh.forEach { campaign ->
            val counts = recipients.countByCampaignAndStatuses(campaign.id)
            val sent = counts[RecipientStatus.SENT] ?: 0L
            val failed = counts[RecipientStatus.FAILED] ?: 0L
            val pending = counts[RecipientStatus.PENDING] ?: 0L
            val skipped = counts
                .filterKeys { it !in setOf(RecipientStatus.SENT, RecipientStatus.FAILED, RecipientStatus.PENDING) }
                .values.sum()
            val total = counts.values.sum()

            val finished = campaign.status == CampaignStatus.SENDING && pending == 0L
            campaigns.save(
                campaign.copy(
                    recipientsTotal = total.toInt(),
                    recipientsSent = sent.toInt(),
                    recipientsFailed = failed.toInt(),
                    recipientsSkipped = skipped.toInt(),
                    creditsSpent = recipients.countSentSms(campaign.id).toInt(),
                    status = if (finished) CampaignStatus.COMPLETED else campaign.status,
                    completedAt = if (finished) Instant.now() else campaign.completedAt,
                    updatedAt = Instant.now()
                )
            )
        }
    }

}
