package pl.detailing.crm.communication.queue

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface OutboundMessageJpaRepository : JpaRepository<OutboundMessageEntity, UUID> {

    @Query("""
        SELECT m.id FROM OutboundMessageEntity m
        WHERE m.status = :status AND m.scheduledFor <= :now
        ORDER BY m.scheduledFor ASC, m.createdAt ASC
    """)
    fun findDueIds(
        @Param("status") status: OutboundMessageStatus,
        @Param("now") now: Instant,
        pageable: Pageable
    ): List<UUID>

    /**
     * Atomowe „biorę tę wiadomość": zmienia status tylko wtedy, gdy nikt inny nie zdążył.
     * Zwraca liczbę zmienionych wierszy — 0 znaczy, że inny wątek/instancja był pierwszy.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE OutboundMessageEntity m
        SET m.status = :to, m.updatedAt = :now
        WHERE m.id = :id AND m.status = :from
    """)
    fun transition(
        @Param("id") id: UUID,
        @Param("from") from: OutboundMessageStatus,
        @Param("to") to: OutboundMessageStatus,
        @Param("now") now: Instant
    ): Int

    @Query("""
        SELECT m FROM OutboundMessageEntity m
        WHERE m.status = :status AND m.updatedAt < :before
    """)
    fun findByStatusUpdatedBefore(
        @Param("status") status: OutboundMessageStatus,
        @Param("before") before: Instant
    ): List<OutboundMessageEntity>

    fun countByStatus(status: OutboundMessageStatus): Long
}

@Repository
interface OutboundMessageAttachmentJpaRepository : JpaRepository<OutboundMessageAttachmentEntity, UUID> {
    fun findAllByMessageId(messageId: UUID): List<OutboundMessageAttachmentEntity>
}
