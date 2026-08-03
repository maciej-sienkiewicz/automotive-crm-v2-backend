package pl.detailing.crm.campaigns.infrastructure

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import pl.detailing.crm.campaigns.domain.CampaignKind
import pl.detailing.crm.campaigns.domain.CampaignStatus
import pl.detailing.crm.campaigns.domain.RecipientChannel
import pl.detailing.crm.campaigns.domain.RecipientStatus
import java.time.Instant
import java.util.UUID

interface CampaignJpaRepository : JpaRepository<CampaignEntity, UUID> {

    fun findByIdAndStudioId(id: UUID, studioId: UUID): CampaignEntity?

    fun findByStudioIdAndStatusInOrderByCreatedAtDesc(
        studioId: UUID,
        statuses: List<CampaignStatus>
    ): List<CampaignEntity>

    fun countByStudioIdAndStatusIn(studioId: UUID, statuses: List<CampaignStatus>): Long

    fun countByStudioIdAndStatusAndCompletedAtAfter(studioId: UUID, status: CampaignStatus, since: Instant): Long

    @Query(
        """SELECT coalesce(sum(r.recipientsSent), 0) FROM CampaignEntity r
           WHERE r.studioId = :studioId AND r.updatedAt >= :since"""
    )
    fun sumSentMessagesSince(@Param("studioId") studioId: UUID, @Param("since") since: Instant): Long

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        """SELECT c FROM CampaignEntity c
           WHERE c.status = pl.detailing.crm.campaigns.domain.CampaignStatus.SCHEDULED
             AND (c.scheduledAt IS NULL OR c.scheduledAt <= :now)"""
    )
    fun findDueScheduledForUpdate(@Param("now") now: Instant): List<CampaignEntity>

    fun findByStatusAndKind(status: CampaignStatus, kind: CampaignKind): List<CampaignEntity>

    fun findByStatus(status: CampaignStatus): List<CampaignEntity>
}

interface CampaignRecipientJpaRepository : JpaRepository<CampaignRecipientEntity, UUID> {

    @Query(
        value = """SELECT * FROM campaign_recipients
                   WHERE status = 'PENDING' AND scheduled_for <= :now
                   ORDER BY scheduled_for
                   LIMIT :limit
                   FOR UPDATE SKIP LOCKED""",
        nativeQuery = true
    )
    fun findDueForDispatch(@Param("now") now: Instant, @Param("limit") limit: Int): List<CampaignRecipientEntity>

    fun findByCampaignIdAndStudioIdOrderByCreatedAt(campaignId: UUID, studioId: UUID): List<CampaignRecipientEntity>

    fun findByCampaignIdAndStudioIdAndStatusOrderByCreatedAt(
        campaignId: UUID,
        studioId: UUID,
        status: RecipientStatus
    ): List<CampaignRecipientEntity>

    @Query(
        """SELECT r.status AS status, count(r) AS cnt FROM CampaignRecipientEntity r
           WHERE r.campaignId = :campaignId GROUP BY r.status"""
    )
    fun countByStatus(@Param("campaignId") campaignId: UUID): List<StatusCount>

    @Modifying
    @Query(
        """UPDATE CampaignRecipientEntity r
           SET r.status = pl.detailing.crm.campaigns.domain.RecipientStatus.STOPPED
           WHERE r.campaignId = :campaignId
             AND r.status = pl.detailing.crm.campaigns.domain.RecipientStatus.PENDING"""
    )
    fun stopPending(@Param("campaignId") campaignId: UUID): Int

    fun existsByCampaignIdAndStatus(campaignId: UUID, status: RecipientStatus): Boolean

    fun countByCampaignIdAndChannelAndStatus(campaignId: UUID, channel: RecipientChannel, status: RecipientStatus): Long

    fun existsByCampaignIdAndCustomerIdAndChannelAndTriggerSourceVisitId(
        campaignId: UUID,
        customerId: UUID,
        channel: RecipientChannel,
        visitId: UUID
    ): Boolean

    interface StatusCount {
        fun getStatus(): RecipientStatus
        fun getCnt(): Long
    }
}

interface CampaignSettingsJpaRepository : JpaRepository<CampaignSettingsEntity, UUID>

interface CampaignOptOutJpaRepository : JpaRepository<CampaignOptOutEntity, UUID> {
    fun existsByStudioIdAndCustomerId(studioId: UUID, customerId: UUID): Boolean
    fun findByStudioIdAndCustomerIdIn(studioId: UUID, customerIds: Collection<UUID>): List<CampaignOptOutEntity>
}
