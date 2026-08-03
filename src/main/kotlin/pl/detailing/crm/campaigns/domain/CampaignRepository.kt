package pl.detailing.crm.campaigns.domain

import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.util.UUID

interface CampaignRepository {
    fun save(campaign: Campaign): Campaign
    fun findById(id: UUID, studioId: StudioId): Campaign?
    fun findByStudio(studioId: StudioId, statuses: List<CampaignStatus>?, kind: CampaignKind?): List<Campaign>
    fun delete(id: UUID)
    fun countByStatuses(studioId: StudioId, statuses: List<CampaignStatus>): Long
    fun countCompletedSince(studioId: StudioId, since: Instant): Long
    fun sumSentMessagesSince(studioId: StudioId, since: Instant): Long

    /** SCHEDULED one-time campaigns whose scheduled_at has passed — locked for materialization. */
    fun findDueScheduledForUpdate(now: Instant): List<Campaign>
    fun findAllActiveAutomatic(): List<Campaign>
    fun findAllByStatus(status: CampaignStatus): List<Campaign>
}

interface CampaignRecipientRepository {
    fun saveAll(recipients: List<CampaignRecipient>): Int
    fun save(recipient: CampaignRecipient): CampaignRecipient
    fun findDueForDispatch(now: Instant, limit: Int): List<CampaignRecipient>
    fun findByCampaign(campaignId: UUID, studioId: StudioId, status: RecipientStatus?): List<CampaignRecipient>
    fun countByCampaignAndStatuses(campaignId: UUID): Map<RecipientStatus, Long>
    fun stopPending(campaignId: UUID): Int
    fun existsPending(campaignId: UUID): Boolean
    fun countSentSms(campaignId: UUID): Long
    fun existsForTrigger(campaignId: UUID, customerId: UUID, channel: RecipientChannel, visitId: UUID): Boolean
}

interface CampaignSettingsRepository {
    fun findByStudio(studioId: StudioId): CampaignSettings?
    fun save(settings: CampaignSettings): CampaignSettings
}

interface CampaignOptOutRepository {
    fun isOptedOut(studioId: UUID, customerId: UUID): Boolean
    fun optOut(studioId: UUID, customerId: UUID, source: OptOutSource)
    fun findOptedOutCustomerIds(studioId: UUID, customerIds: Collection<UUID>): Set<UUID>
}
