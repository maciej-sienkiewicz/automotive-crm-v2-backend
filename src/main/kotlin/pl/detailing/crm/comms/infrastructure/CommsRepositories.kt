package pl.detailing.crm.comms.infrastructure

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import pl.detailing.crm.comms.domain.CommFolderKind
import pl.detailing.crm.comms.domain.CommOutboxStatus
import java.time.Instant
import java.util.UUID

@Repository
interface CommThreadRepository : JpaRepository<CommThreadEntity, UUID> {

    fun findByIdAndStudioId(id: UUID, studioId: UUID): CommThreadEntity?

    @Query(
        """SELECT t FROM CommThreadEntity t
           WHERE t.studioId = :studioId
             AND (:accountId IS NULL OR t.accountId = :accountId)
             AND t.archived = :archived
             AND (:labelId IS NULL OR t.labelId = :labelId)
             AND (:onlyUnread = FALSE OR t.unreadCount > 0)
             AND (:onlyLeads = FALSE OR t.leadId IS NOT NULL)
             AND (:query IS NULL
                  OR LOWER(t.participantEmail) LIKE CONCAT('%', LOWER(CAST(:query AS string)), '%')
                  OR LOWER(COALESCE(t.participantName, '')) LIKE CONCAT('%', LOWER(CAST(:query AS string)), '%')
                  OR LOWER(COALESCE(t.subject, '')) LIKE CONCAT('%', LOWER(CAST(:query AS string)), '%'))
           ORDER BY t.lastMessageAt DESC"""
    )
    fun search(
        @Param("studioId") studioId: UUID,
        @Param("accountId") accountId: UUID?,
        @Param("archived") archived: Boolean,
        @Param("labelId") labelId: UUID?,
        @Param("onlyUnread") onlyUnread: Boolean,
        @Param("onlyLeads") onlyLeads: Boolean,
        @Param("query") query: String?,
        pageable: Pageable
    ): Page<CommThreadEntity>

    /** Threading cascade, step 2: same normalised subject + same external participant, recent. */
    @Query(
        """SELECT t FROM CommThreadEntity t
           WHERE t.accountId = :accountId
             AND t.subjectNorm = :subjectNorm
             AND t.participantEmail = :participantEmail
             AND t.lastMessageAt >= :since
           ORDER BY t.lastMessageAt DESC"""
    )
    fun findRecentBySubjectAndParticipant(
        @Param("accountId") accountId: UUID,
        @Param("subjectNorm") subjectNorm: String,
        @Param("participantEmail") participantEmail: String,
        @Param("since") since: Instant
    ): List<CommThreadEntity>

    fun findByStudioIdAndParticipantEmailOrderByLastMessageAtDesc(
        studioId: UUID,
        participantEmail: String,
        pageable: Pageable
    ): List<CommThreadEntity>

    @Query("SELECT COALESCE(SUM(t.unreadCount), 0) FROM CommThreadEntity t WHERE t.studioId = :studioId AND t.archived = FALSE")
    fun countUnread(@Param("studioId") studioId: UUID): Long
}

@Repository
interface CommMessageRepository : JpaRepository<CommMessageEntity, UUID> {

    fun findByIdAndStudioId(id: UUID, studioId: UUID): CommMessageEntity?

    fun findByAccountIdAndMessageIdHdr(accountId: UUID, messageIdHdr: String): CommMessageEntity?

    fun findByThreadIdOrderBySentAtAsc(threadId: UUID): List<CommMessageEntity>

    fun findFirstByThreadIdOrderBySentAtDesc(threadId: UUID): CommMessageEntity?

    /** Thread resolution by RFC 5322 ancestry: any known message with one of these ids. */
    @Query(
        """SELECT m FROM CommMessageEntity m
           WHERE m.accountId = :accountId AND m.messageIdHdr IN :messageIds
           ORDER BY m.sentAt DESC"""
    )
    fun findByAccountIdAndMessageIdHdrIn(
        @Param("accountId") accountId: UUID,
        @Param("messageIds") messageIds: Collection<String>
    ): List<CommMessageEntity>

    /**
     * The read-reconciliation working set: still-unread INBOX messages that have an IMAP
     * identity. Only these need their \Seen flag checked — a read message never goes back.
     */
    @Query(
        """SELECT m FROM CommMessageEntity m
           WHERE m.accountId = :accountId
             AND m.folderKind = :folderKind
             AND m.isRead = FALSE
             AND m.imapUid IS NOT NULL
             AND m.imapUidValidity = :uidValidity"""
    )
    fun findUnreadWithUid(
        @Param("accountId") accountId: UUID,
        @Param("folderKind") folderKind: CommFolderKind,
        @Param("uidValidity") uidValidity: Long
    ): List<CommMessageEntity>

    @Query(
        """SELECT m FROM CommMessageEntity m
           WHERE m.studioId = :studioId AND m.fromEmail = :fromEmail AND m.direction = 'INBOUND'
           ORDER BY m.sentAt DESC"""
    )
    fun findRecentInboundFrom(
        @Param("studioId") studioId: UUID,
        @Param("fromEmail") fromEmail: String,
        pageable: Pageable
    ): List<CommMessageEntity>
}

@Repository
interface CommAttachmentRepository : JpaRepository<CommAttachmentEntity, UUID> {

    fun findByIdAndStudioId(id: UUID, studioId: UUID): CommAttachmentEntity?

    fun findByMessageIdAndContentId(messageId: UUID, contentId: String): CommAttachmentEntity?

    @Query(
        """SELECT a.id AS id, a.messageId AS messageId, a.fileName AS fileName,
                  a.contentType AS contentType, a.contentId AS contentId,
                  a.isInline AS isInline, a.sizeBytes AS sizeBytes
           FROM CommAttachmentEntity a WHERE a.messageId IN :messageIds"""
    )
    fun findMetaByMessageIdIn(@Param("messageIds") messageIds: Collection<UUID>): List<CommAttachmentMeta>
}

/** Metadata projection — keeps attachment bytes out of message queries. */
interface CommAttachmentMeta {
    val id: UUID
    val messageId: UUID
    val fileName: String
    val contentType: String
    val contentId: String?
    val isInline: Boolean
    val sizeBytes: Long
}

@Repository
interface CommLabelRepository : JpaRepository<CommLabelEntity, UUID> {
    fun findByStudioIdOrderByPositionAsc(studioId: UUID): List<CommLabelEntity>
    fun findByIdAndStudioId(id: UUID, studioId: UUID): CommLabelEntity?
}

@Repository
interface CommOutboxRepository : JpaRepository<CommOutboxEntity, UUID> {

    @Query(
        """SELECT o FROM CommOutboxEntity o
           WHERE o.status = 'PENDING' AND o.nextAttemptAt <= :now
           ORDER BY o.createdAt ASC"""
    )
    fun findDue(@Param("now") now: Instant, pageable: Pageable): List<CommOutboxEntity>

    @Modifying
    @Query("DELETE FROM CommOutboxEntity o WHERE o.status = :status AND o.createdAt < :before")
    fun deleteOldByStatus(@Param("status") status: CommOutboxStatus, @Param("before") before: Instant): Int
}
