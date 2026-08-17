package pl.detailing.crm.mailbox.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import pl.detailing.crm.mailbox.domain.MailAccountStatus
import pl.detailing.crm.mailbox.domain.MailProviderType
import pl.detailing.crm.mailbox.domain.MailThreadClassification
import java.time.Instant
import java.util.UUID

@Repository
interface MailAccountRepository : JpaRepository<MailAccountEntity, UUID> {
    fun findByStudioId(studioId: UUID): List<MailAccountEntity>
    fun findByIdAndStudioId(id: UUID, studioId: UUID): MailAccountEntity?
    fun findByStudioIdAndEmailAddress(studioId: UUID, emailAddress: String): MailAccountEntity?
    fun findByStatusAndProviderType(
        status: MailAccountStatus,
        providerType: MailProviderType
    ): List<MailAccountEntity>
}

@Repository
interface MailThreadRepository : JpaRepository<MailThreadEntity, UUID> {
    fun findByIdAndStudioId(id: UUID, studioId: UUID): MailThreadEntity?
    fun findByStudioIdAndExternalThreadKey(studioId: UUID, externalThreadKey: String): MailThreadEntity?
    fun findByLeadId(leadId: UUID): MailThreadEntity?
    fun findByStudioIdAndClassificationOrderByLastMessageAtDesc(
        studioId: UUID,
        classification: MailThreadClassification
    ): List<MailThreadEntity>

    /**
     * Last resort of the threading cascade: same normalized subject and the same participant
     * within the recency window. Requiring the participant match prevents two different clients
     * writing "Wycena" from being merged into one thread.
     */
    @Query(
        """SELECT t FROM MailThreadEntity t
           WHERE t.studioId = :studioId
             AND t.subjectNorm = :subjectNorm
             AND t.lastMessageAt >= :since
             AND EXISTS (
                 SELECT m FROM MailMessageEntity m
                 WHERE m.threadId = t.id AND m.fromEmail = :participant
             )
           ORDER BY t.lastMessageAt DESC"""
    )
    fun findRecentBySubjectAndParticipant(
        @Param("studioId") studioId: UUID,
        @Param("subjectNorm") subjectNorm: String,
        @Param("participant") participant: String,
        @Param("since") since: Instant
    ): List<MailThreadEntity>
}

@Repository
interface MailMessageRepository : JpaRepository<MailMessageEntity, UUID> {
    fun findByStudioIdAndMessageId(studioId: UUID, messageId: String): MailMessageEntity?
    fun findByThreadIdOrderBySentAtAsc(threadId: UUID): List<MailMessageEntity>
    fun findFirstByThreadIdOrderBySentAtAsc(threadId: UUID): MailMessageEntity?
    fun findFirstByThreadIdOrderBySentAtDesc(threadId: UUID): MailMessageEntity?
}

@Repository
interface MailSenderRuleRepository : JpaRepository<MailSenderRuleEntity, UUID> {
    fun findByStudioIdAndPatternIn(studioId: UUID, patterns: Collection<String>): List<MailSenderRuleEntity>
}
