package pl.detailing.crm.campaigns.infrastructure

import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Repository
import pl.detailing.crm.campaigns.domain.*
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.util.UUID

@Repository
class CampaignRepositoryAdapter(
    private val jpa: CampaignJpaRepository
) : CampaignRepository {

    override fun save(campaign: Campaign): Campaign =
        jpa.save(CampaignEntity.fromDomain(campaign)).toDomain()

    override fun findById(id: UUID, studioId: StudioId): Campaign? =
        jpa.findByIdAndStudioId(id, studioId.value)?.toDomain()

    override fun findByStudio(
        studioId: StudioId,
        statuses: List<CampaignStatus>?,
        kind: CampaignKind?
    ): List<Campaign> =
        jpa.findByStudioIdAndStatusInOrderByCreatedAtDesc(studioId.value, statuses ?: CampaignStatus.entries)
            .map { it.toDomain() }
            .filter { kind == null || it.kind == kind }

    override fun delete(id: UUID) = jpa.deleteById(id)

    override fun countByStatuses(studioId: StudioId, statuses: List<CampaignStatus>): Long =
        jpa.countByStudioIdAndStatusIn(studioId.value, statuses)

    override fun countCompletedSince(studioId: StudioId, since: Instant): Long =
        jpa.countByStudioIdAndStatusAndCompletedAtAfter(studioId.value, CampaignStatus.COMPLETED, since)

    override fun sumSentMessagesSince(studioId: StudioId, since: Instant): Long =
        jpa.sumSentMessagesSince(studioId.value, since)

    override fun findDueScheduledForUpdate(now: Instant): List<Campaign> =
        jpa.findDueScheduledForUpdate(now).map { it.toDomain() }

    override fun findAllActiveAutomatic(): List<Campaign> =
        jpa.findByStatusAndKind(CampaignStatus.ACTIVE, CampaignKind.AUTOMATIC).map { it.toDomain() }

    override fun findAllByStatus(status: CampaignStatus): List<Campaign> =
        jpa.findByStatus(status).map { it.toDomain() }
}

@Repository
class CampaignRecipientRepositoryAdapter(
    private val jpa: CampaignRecipientJpaRepository
) : CampaignRecipientRepository {

    override fun saveAll(recipients: List<CampaignRecipient>): Int =
        jpa.saveAll(recipients.map { CampaignRecipientEntity.fromDomain(it) }).size

    override fun save(recipient: CampaignRecipient): CampaignRecipient =
        jpa.save(CampaignRecipientEntity.fromDomain(recipient)).toDomain()

    override fun findDueForDispatch(now: Instant, limit: Int): List<CampaignRecipient> =
        jpa.findDueForDispatch(now, limit).map { it.toDomain() }

    override fun findByCampaign(
        campaignId: UUID,
        studioId: StudioId,
        status: RecipientStatus?
    ): List<CampaignRecipient> =
        (if (status == null) jpa.findByCampaignIdAndStudioIdOrderByCreatedAt(campaignId, studioId.value)
         else jpa.findByCampaignIdAndStudioIdAndStatusOrderByCreatedAt(campaignId, studioId.value, status))
            .map { it.toDomain() }

    override fun countByCampaignAndStatuses(campaignId: UUID): Map<RecipientStatus, Long> =
        jpa.countByStatus(campaignId).associate { it.getStatus() to it.getCnt() }

    override fun stopPending(campaignId: UUID): Int = jpa.stopPending(campaignId)

    override fun existsPending(campaignId: UUID): Boolean =
        jpa.existsByCampaignIdAndStatus(campaignId, RecipientStatus.PENDING)

    override fun countSentSms(campaignId: UUID): Long =
        jpa.countByCampaignIdAndChannelAndStatus(campaignId, RecipientChannel.SMS, RecipientStatus.SENT)

    override fun existsForTrigger(
        campaignId: UUID,
        customerId: UUID,
        channel: RecipientChannel,
        visitId: UUID
    ): Boolean =
        jpa.existsByCampaignIdAndCustomerIdAndChannelAndTriggerSourceVisitId(campaignId, customerId, channel, visitId)
}

@Repository
class CampaignSettingsRepositoryAdapter(
    private val jpa: CampaignSettingsJpaRepository
) : CampaignSettingsRepository {

    override fun findByStudio(studioId: StudioId): CampaignSettings? =
        jpa.findByIdOrNull(studioId.value)?.toDomain()

    override fun save(settings: CampaignSettings): CampaignSettings =
        jpa.save(CampaignSettingsEntity.fromDomain(settings)).toDomain()
}

@Repository
class CampaignOptOutRepositoryAdapter(
    private val jpa: CampaignOptOutJpaRepository
) : CampaignOptOutRepository {

    override fun isOptedOut(studioId: UUID, customerId: UUID): Boolean =
        jpa.existsByStudioIdAndCustomerId(studioId, customerId)

    override fun optOut(studioId: UUID, customerId: UUID, source: OptOutSource) {
        if (jpa.existsByStudioIdAndCustomerId(studioId, customerId)) return
        jpa.save(
            CampaignOptOutEntity(
                id = UUID.randomUUID(),
                studioId = studioId,
                customerId = customerId,
                source = source,
                optedOutAt = Instant.now()
            )
        )
    }

    override fun findOptedOutCustomerIds(studioId: UUID, customerIds: Collection<UUID>): Set<UUID> =
        if (customerIds.isEmpty()) emptySet()
        else jpa.findByStudioIdAndCustomerIdIn(studioId, customerIds).map { it.customerId }.toSet()
}
