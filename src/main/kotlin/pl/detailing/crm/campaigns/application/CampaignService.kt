package pl.detailing.crm.campaigns.application

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditActor
import pl.detailing.crm.audit.domain.AuditActorResolver
import pl.detailing.crm.audit.domain.AuditEvent
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.communication.template.MessageTemplateKind
import pl.detailing.crm.communication.template.MessageTemplateRenderer
import pl.detailing.crm.campaigns.domain.*
import pl.detailing.crm.campaigns.infrastructure.AudienceEstimate
import pl.detailing.crm.campaigns.infrastructure.AudienceQueryService
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.smscredits.SmsCreditService
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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

/**
 * Warunek kampanii automatycznej w wersji „na próbę".
 *
 * Osobny od [TriggerConfig], bo kreator pyta o prognozę zanim warunek jest kompletny:
 * [TriggerConfig] odrzuca pustą listę usług, a w kreatorze pusta lista to normalny stan
 * pierwszej sekundy. Niesie też [horizonDays] — okno, w którym patrzymy w przód.
 */
data class TriggerProjection(
    val serviceIds: List<UUID>,
    val afterDays: Int,
    val onlyIfNoVisitSince: Boolean,
    val horizonDays: Int
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
    private val smsCreditService: SmsCreditService,
    private val auditService: AuditService,
    private val auditActorResolver: AuditActorResolver,
    private val templateRenderer: MessageTemplateRenderer
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

    /**
     * Kto dostanie tę kampanię — i jedna strona tej listy.
     *
     * Dla kampanii jednorazowej odpowiedź wynika wprost z kryteriów odbiorców.
     * Dla automatycznej kryteria są dopiero drugim sitem: pierwszym jest warunek
     * (usługa wykonana dokładnie [TriggerProjection.afterDays] dni temu). Bez
     * [trigger] kreator kampanii automatycznej pokazywałby całą bazę klientów —
     * liczbę, która nie ma nic wspólnego z tym, ilu ludzi ta kampania odezwie.
     *
     * Prognoza dla automatu patrzy w przód o [TriggerProjection.horizonDays]: „dziś"
     * to zwykle zero osób, a pytanie brzmi „ilu klientów przejdzie przez ten warunek
     * w najbliższym miesiącu".
     */
    @Transactional(readOnly = true)
    fun estimateAudience(
        studioId: StudioId,
        criteria: AudienceCriteria,
        channel: RecipientChannel,
        trigger: TriggerProjection? = null,
        sampleLimit: Int = 50,
        sampleOffset: Int = 0
    ): AudienceEstimate {
        val settings = getSettings(studioId)
        val candidates = trigger?.let { triggerCandidates(studioId, it) }
        // Warunek bez wskazanej usługi nie wskazuje nikogo — i tak trzeba to powiedzieć
        // wprost, zamiast pokazywać liczbę wziętą z całej bazy.
        if (trigger != null && candidates.isNullOrEmpty()) return AudienceEstimate.EMPTY
        return audienceQuery.estimate(
            studioId, criteria, channel, settings.frequencyCapDays,
            sampleLimit = sampleLimit,
            sampleOffset = sampleOffset,
            candidateCustomerIds = candidates
        )
    }

    /**
     * Klienci, u których warunek zadziała w oknie prognozy. Odbicie okna, które co
     * godzinę liczy [AutomaticCampaignEnroller]: w dniu D warunek obejmuje wizyty
     * odebrane D − afterDays, więc przez najbliższe H dni obejmie wizyty odebrane
     * w przedziale [dziś − afterDays, dziś − afterDays + H).
     */
    private fun triggerCandidates(studioId: StudioId, trigger: TriggerProjection): List<UUID> {
        if (trigger.serviceIds.isEmpty()) return emptyList()
        val warsaw = ZoneId.of("Europe/Warsaw")
        val firstPickupDay: LocalDate = LocalDate.now(warsaw).minusDays(trigger.afterDays.toLong())
        return audienceQuery.findTriggeredVisits(
            studioId = studioId,
            serviceIds = trigger.serviceIds,
            pickupFrom = firstPickupDay.atStartOfDay(warsaw).toInstant(),
            pickupTo = firstPickupDay.plusDays(trigger.horizonDays.toLong()).atStartOfDay(warsaw).toInstant(),
            onlyIfNoVisitSince = trigger.onlyIfNoVisitSince
        ).map { it.customerId }.distinct()
    }

    // ─── Create / update / delete ────────────────────────────────────────────

    /**
     * A campaign is written once and sent to thousands of people, so an unknown
     * placeholder has to be caught while the studio is still looking at the wizard.
     */
    private fun validateTemplates(cmd: CreateCampaignCommand) {
        val kind = MessageTemplateKind.CAMPAIGN
        cmd.smsTemplate?.let { kind.validate(it, templateRenderer, "Treść SMS") }
        cmd.emailSubject?.let { kind.validate(it, templateRenderer, "Temat e-maila") }
        cmd.emailBody?.let { kind.validate(it, templateRenderer, "Treść e-maila") }
    }

    @Transactional
    fun create(studioId: StudioId, userId: UserId, cmd: CreateCampaignCommand): Campaign {
        if (cmd.name.isBlank()) throw ValidationException("Nazwa kampanii nie może być pusta")
        validateTemplates(cmd)
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
        val saved = campaigns.save(campaign)

        recordCampaignEvent(saved, userId, AuditAction.CAMPAIGN_CREATED)

        return saved
    }

    @Transactional
    fun update(id: UUID, studioId: StudioId, userId: UserId, cmd: CreateCampaignCommand): Campaign {
        val existing = get(id, studioId)
        existing.requireEditable()
        if (cmd.name.isBlank()) throw ValidationException("Nazwa kampanii nie może być pusta")
        validateTemplates(cmd)
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
        val saved = campaigns.save(updated)

        recordCampaignEvent(
            campaign = saved,
            userId = userId,
            action = AuditAction.CAMPAIGN_UPDATED,
            changes = listOfNotNull(
                FieldChange("campaignName", existing.name, saved.name).takeIf { existing.name != saved.name },
                FieldChange("messageChannel", existing.channel.name, saved.channel.name)
                    .takeIf { existing.channel != saved.channel }
            )
        )

        return saved
    }

    @Transactional
    fun delete(id: UUID, studioId: StudioId) {
        val campaign = get(id, studioId)
        if (campaign.status != CampaignStatus.DRAFT) {
            throw ValidationException("Usunąć można tylko szkic kampanii")
        }
        campaigns.delete(id)

        recordCampaignEvent(campaign, campaign.updatedBy, AuditAction.CAMPAIGN_DELETED)
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
        val saved = campaigns.save(
            campaign.copy(
                status = CampaignStatus.SCHEDULED,
                scheduledAt = scheduledAt,
                updatedBy = userId,
                updatedAt = Instant.now()
            )
        )

        // Scheduling is the moment a campaign starts costing money and reaching customers,
        // so it is the launch as far as the owner's feed is concerned — not the later,
        // automated transition to SENDING.
        recordCampaignEvent(
            campaign = saved,
            userId = userId,
            action = AuditAction.CAMPAIGN_LAUNCHED,
            changes = listOf(
                FieldChange("status", campaign.status.name, CampaignStatus.SCHEDULED.name),
                FieldChange("startDate", null, (scheduledAt ?: Instant.now()).toString())
            )
        )

        return saved
    }

    @Transactional
    fun cancel(id: UUID, studioId: StudioId, userId: UserId): Campaign {
        val campaign = get(id, studioId)
        if (campaign.status != CampaignStatus.SCHEDULED) {
            throw ValidationException("Anulować można tylko zaplanowaną kampanię")
        }
        val saved = campaigns.save(
            campaign.copy(status = CampaignStatus.CANCELLED, updatedBy = userId, updatedAt = Instant.now())
        )

        recordCampaignEvent(
            campaign = saved,
            userId = userId,
            action = AuditAction.CAMPAIGN_CANCELLED,
            changes = listOf(FieldChange("status", campaign.status.name, CampaignStatus.CANCELLED.name))
        )

        return saved
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
        val saved = campaigns.save(campaign.copy(status = to, updatedBy = userId, updatedAt = Instant.now()))

        recordCampaignEvent(
            campaign = saved,
            userId = userId,
            action = when (to) {
                // Activating an automatic campaign starts it sending on its own trigger,
                // which is the same commitment as scheduling a one-off.
                CampaignStatus.ACTIVE -> AuditAction.CAMPAIGN_LAUNCHED
                CampaignStatus.PAUSED, CampaignStatus.ARCHIVED -> AuditAction.CAMPAIGN_CANCELLED
                else -> AuditAction.CAMPAIGN_UPDATED
            },
            changes = listOf(FieldChange("status", campaign.status.name, to.name))
        )

        return saved
    }

    /**
     * Campaigns reach customers and spend SMS credits, so every lifecycle change belongs in
     * the owner's feed. [AuditActorResolver] supplies the acting user's name; the campaign's
     * own [Campaign.updatedBy] is the fallback when the security context is not reachable.
     */
    private fun recordCampaignEvent(
        campaign: Campaign,
        userId: UserId,
        action: AuditAction,
        changes: List<FieldChange> = emptyList()
    ) {
        auditService.recordSync(
            AuditEvent(
                studioId = campaign.studioId,
                actor = auditActorResolver.current(AuditActor.employee(userId, null)),
                module = AuditModule.CAMPAIGN,
                action = action,
                entityId = campaign.id.toString(),
                entityDisplayName = campaign.name,
                changes = changes,
                metadata = mapOf(
                    "kind" to campaign.kind.name,
                    "channel" to campaign.channel.name,
                    "status" to campaign.status.name,
                    "recipientsTotal" to campaign.recipientsTotal.toString()
                )
            )
        )
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
