package pl.detailing.crm.campaigns.application

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.campaigns.domain.*
import pl.detailing.crm.campaigns.infrastructure.AudienceEstimate
import pl.detailing.crm.campaigns.infrastructure.AudienceQueryService
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.smscredits.SmsCreditService
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

data class CampaignStats(
    val active: Long,
    val scheduled: Long,
    val completedTotal: Long,
    val completedLast30Days: Long,
    val messagesSentLast30Days: Long,
    val smsCreditsAvailable: Int
)

data class CreateCampaignCommand(
    val name: String,
    val kind: CampaignKind,
    val channel: CampaignChannel,
    val audience: AudienceCriteria,
    val smsTemplate: String?,
    val emailSubject: String?,
    val emailBody: String?,
    val scheduledAt: Instant?,
    val trigger: TriggerConfig?
)

@Service
class CampaignService(
    private val campaigns: CampaignRepository,
    private val recipients: CampaignRecipientRepository,
    private val settingsRepository: CampaignSettingsRepository,
    private val audienceQuery: AudienceQueryService,
    private val smsCreditService: SmsCreditService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // ─── Read ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun list(studioId: StudioId, statuses: List<CampaignStatus>?, kind: CampaignKind?): List<Campaign> =
        campaigns.findByStudio(studioId, statuses, kind)

    @Transactional(readOnly = true)
    fun get(id: UUID, studioId: StudioId): Campaign =
        campaigns.findById(id, studioId)
            ?: throw EntityNotFoundException("Kampania nie istnieje")

    @Transactional(readOnly = true)
    fun stats(studioId: StudioId): CampaignStats {
        val since = Instant.now().minus(30, ChronoUnit.DAYS)
        return CampaignStats(
            active = campaigns.countByStatuses(studioId, listOf(CampaignStatus.ACTIVE, CampaignStatus.SENDING)),
            scheduled = campaigns.countByStatuses(studioId, listOf(CampaignStatus.SCHEDULED)),
            completedTotal = campaigns.countByStatuses(studioId, listOf(CampaignStatus.COMPLETED)),
            completedLast30Days = campaigns.countCompletedSince(studioId, since),
            messagesSentLast30Days = campaigns.sumSentMessagesSince(studioId, since),
            smsCreditsAvailable = smsCreditService.getBalance(studioId).availableCredits
        )
    }

    @Transactional(readOnly = true)
    fun recipientsOf(id: UUID, studioId: StudioId, status: RecipientStatus?): List<CampaignRecipient> {
        get(id, studioId) // tenant check
        return recipients.findByCampaign(id, studioId, status)
    }

    @Transactional(readOnly = true)
    fun estimateAudience(
        studioId: StudioId,
        criteria: AudienceCriteria,
        channel: RecipientChannel
    ): AudienceEstimate {
        val settings = getSettings(studioId)
        return audienceQuery.estimate(studioId, criteria, channel, settings.frequencyCapDays)
    }

    // ─── Create / update / delete ────────────────────────────────────────────

    @Transactional
    fun create(studioId: StudioId, userId: UserId, cmd: CreateCampaignCommand): Campaign {
        if (cmd.name.isBlank()) throw ValidationException("Nazwa kampanii nie może być pusta")
        val now = Instant.now()
        val campaign = Campaign(
            id = UUID.randomUUID(),
            studioId = studioId,
            name = cmd.name.trim(),
            kind = cmd.kind,
            channel = cmd.channel,
            status = CampaignStatus.DRAFT,
            audience = cmd.audience,
            smsTemplate = cmd.smsTemplate,
            emailSubject = cmd.emailSubject,
            emailBody = cmd.emailBody,
            scheduledAt = cmd.scheduledAt,
            trigger = cmd.trigger,
            createdBy = userId,
            updatedBy = userId,
            createdAt = now,
            updatedAt = now
        )
        return campaigns.save(campaign)
    }

    @Transactional
    fun update(id: UUID, studioId: StudioId, userId: UserId, cmd: CreateCampaignCommand): Campaign {
        val existing = get(id, studioId)
        existing.requireEditable()
        if (cmd.name.isBlank()) throw ValidationException("Nazwa kampanii nie może być pusta")
        val updated = existing.copy(
            name = cmd.name.trim(),
            channel = cmd.channel,
            audience = cmd.audience,
            smsTemplate = cmd.smsTemplate,
            emailSubject = cmd.emailSubject,
            emailBody = cmd.emailBody,
            scheduledAt = cmd.scheduledAt,
            trigger = cmd.trigger,
            updatedBy = userId,
            updatedAt = Instant.now()
        )
        return campaigns.save(updated)
    }

    @Transactional
    fun delete(id: UUID, studioId: StudioId) {
        val campaign = get(id, studioId)
        if (campaign.status != CampaignStatus.DRAFT) {
            throw ValidationException("Usunąć można tylko szkic kampanii")
        }
        campaigns.delete(id)
    }

    @Transactional
    fun duplicate(id: UUID, studioId: StudioId, userId: UserId): Campaign {
        val source = get(id, studioId)
        val now = Instant.now()
        return campaigns.save(
            source.copy(
                id = UUID.randomUUID(),
                name = "${source.name} (kopia)",
                status = CampaignStatus.DRAFT,
                scheduledAt = null,
                recipientsTotal = 0, recipientsSent = 0, recipientsFailed = 0,
                recipientsSkipped = 0, creditsSpent = 0,
                createdBy = userId, updatedBy = userId,
                createdAt = now, updatedAt = now,
                startedAt = null, completedAt = null
            )
        )
    }

    // ─── Lifecycle ───────────────────────────────────────────────────────────

    /** DRAFT → SCHEDULED. [scheduledAt] null = wysyłka natychmiast (materializer podejmie w ciągu minuty). */
    @Transactional
    fun schedule(id: UUID, studioId: StudioId, userId: UserId, scheduledAt: Instant?): Campaign {
        val campaign = get(id, studioId)
        if (campaign.kind != CampaignKind.ONE_TIME) {
            throw ValidationException("Kampanię automatyczną aktywuje się, a nie planuje")
        }
        if (campaign.status !in setOf(CampaignStatus.DRAFT, CampaignStatus.SCHEDULED)) {
            throw ValidationException("Zaplanować można tylko szkic kampanii")
        }
        campaign.validateContent()
        if (scheduledAt != null && scheduledAt.isBefore(Instant.now().minusSeconds(60))) {
            throw ValidationException("Termin wysyłki nie może być w przeszłości")
        }
        return campaigns.save(
            campaign.copy(
                status = CampaignStatus.SCHEDULED,
                scheduledAt = scheduledAt,
                updatedBy = userId,
                updatedAt = Instant.now()
            )
        )
    }

    @Transactional
    fun cancel(id: UUID, studioId: StudioId, userId: UserId): Campaign {
        val campaign = get(id, studioId)
        if (campaign.status != CampaignStatus.SCHEDULED) {
            throw ValidationException("Anulować można tylko zaplanowaną kampanię")
        }
        return campaigns.save(
            campaign.copy(status = CampaignStatus.CANCELLED, updatedBy = userId, updatedAt = Instant.now())
        )
    }

    /** SENDING → COMPLETED, pozostali PENDING dostają status STOPPED. */
    @Transactional
    fun stopSending(id: UUID, studioId: StudioId, userId: UserId): Campaign {
        val campaign = get(id, studioId)
        if (campaign.status != CampaignStatus.SENDING) {
            throw ValidationException("Zatrzymać można tylko kampanię w trakcie wysyłki")
        }
        val stopped = recipients.stopPending(id)
        logger.info("Campaign {} stopped by user — {} pending recipients marked STOPPED", id, stopped)
        return campaigns.save(
            campaign.copy(
                status = CampaignStatus.COMPLETED,
                completedAt = Instant.now(),
                updatedBy = userId,
                updatedAt = Instant.now()
            )
        )
    }

    @Transactional
    fun activate(id: UUID, studioId: StudioId, userId: UserId): Campaign =
        transitionAutomatic(id, studioId, userId, from = setOf(CampaignStatus.DRAFT, CampaignStatus.PAUSED), to = CampaignStatus.ACTIVE)

    @Transactional
    fun pause(id: UUID, studioId: StudioId, userId: UserId): Campaign =
        transitionAutomatic(id, studioId, userId, from = setOf(CampaignStatus.ACTIVE), to = CampaignStatus.PAUSED)

    @Transactional
    fun archive(id: UUID, studioId: StudioId, userId: UserId): Campaign =
        transitionAutomatic(id, studioId, userId, from = setOf(CampaignStatus.ACTIVE, CampaignStatus.PAUSED), to = CampaignStatus.ARCHIVED)

    private fun transitionAutomatic(
        id: UUID,
        studioId: StudioId,
        userId: UserId,
        from: Set<CampaignStatus>,
        to: CampaignStatus
    ): Campaign {
        val campaign = get(id, studioId)
        if (campaign.kind != CampaignKind.AUTOMATIC) {
            throw ValidationException("Ta operacja dotyczy tylko kampanii automatycznych")
        }
        if (campaign.status !in from) {
            throw ValidationException("Nieprawidłowy status kampanii dla tej operacji")
        }
        if (to == CampaignStatus.ACTIVE) campaign.validateContent()
        return campaigns.save(campaign.copy(status = to, updatedBy = userId, updatedAt = Instant.now()))
    }

    // ─── Retry ───────────────────────────────────────────────────────────────────

    @Transactional
    fun retryRecipient(campaignId: UUID, recipientId: UUID, studioId: StudioId): CampaignRecipient {
        val campaign = get(campaignId, studioId)
        val recipient = recipients.findById(recipientId, campaignId, studioId)
            ?: throw EntityNotFoundException("Odbiorca nie istnieje")
        if (recipient.status in setOf(RecipientStatus.SENT, RecipientStatus.PENDING, RecipientStatus.EXCLUDED_MANUALLY, RecipientStatus.SKIPPED_OPTED_OUT)) {
            throw ValidationException("Ten rekord nie może być ponowiony")
        }
        val retried = recipients.save(
            recipient.copy(status = RecipientStatus.PENDING, errorMessage = null, sentAt = null, scheduledFor = Instant.now())
        )
        if (campaign.status == CampaignStatus.COMPLETED || campaign.status == CampaignStatus.FAILED) {
            campaigns.save(campaign.copy(status = CampaignStatus.SENDING, completedAt = null, updatedAt = Instant.now()))
        }
        return retried
    }

    @Transactional
    fun retryAllFailed(campaignId: UUID, studioId: StudioId): Int {
        val campaign = get(campaignId, studioId)
        val retryableStatuses = setOf(
            RecipientStatus.FAILED, RecipientStatus.STOPPED,
            RecipientStatus.SKIPPED_NO_CREDITS, RecipientStatus.SKIPPED_FREQUENCY_CAP
        )
        val toRetry = recipients.findByCampaign(campaignId, studioId, null)
            .filter { it.status in retryableStatuses }
        if (toRetry.isEmpty()) return 0
        val now = Instant.now()
        toRetry.forEach { r ->
            recipients.save(r.copy(status = RecipientStatus.PENDING, errorMessage = null, sentAt = null, scheduledFor = now))
        }
        if (campaign.status == CampaignStatus.COMPLETED || campaign.status == CampaignStatus.FAILED) {
            campaigns.save(campaign.copy(status = CampaignStatus.SENDING, completedAt = null, updatedAt = now))
        }
        return toRetry.size
    }

    // ─── Settings ────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun getSettings(studioId: StudioId): CampaignSettings =
        settingsRepository.findByStudio(studioId) ?: CampaignSettings.defaultFor(studioId)

    @Transactional
    fun updateSettings(studioId: StudioId, settings: CampaignSettings): CampaignSettings {
        if (settings.frequencyCapDays < 0) throw ValidationException("Limit częstotliwości nie może być ujemny")
        return settingsRepository.save(settings.copy(studioId = studioId, updatedAt = Instant.now()))
    }
}
