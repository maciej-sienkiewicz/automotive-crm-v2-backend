package pl.detailing.crm.comms.engine

import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.comms.domain.CommDirection
import pl.detailing.crm.comms.domain.CommMessageReadEvent
import pl.detailing.crm.comms.domain.CommOutboxStatus
import pl.detailing.crm.comms.domain.CommOutboxType
import pl.detailing.crm.comms.domain.CommReadSource
import pl.detailing.crm.comms.infrastructure.CommMessageEntity
import pl.detailing.crm.comms.infrastructure.CommMessageRepository
import pl.detailing.crm.comms.infrastructure.CommOutboxEntity
import pl.detailing.crm.comms.infrastructure.CommOutboxRepository
import pl.detailing.crm.comms.infrastructure.CommThreadRepository
import pl.detailing.crm.shared.NotFoundException
import java.time.Instant
import java.util.UUID

/**
 * Single owner of the read/unread transition, in both directions.
 *
 * CRM → server: the user opens a message here; the local state flips immediately
 * (optimistic UI) and a MARK_SEEN command is queued for IMAP. Server → CRM: the sync
 * engine noticed the \Seen flag set by an external client. The transition is
 * monotonic — a message never goes back to unread — which is what makes the whole
 * reconciliation cheap.
 */
@Service
class CommsReadService(
    private val messageRepository: CommMessageRepository,
    private val threadRepository: CommThreadRepository,
    private val outboxRepository: CommOutboxRepository,
    private val eventPublisher: ApplicationEventPublisher
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** User opened the message in the CRM. Idempotent. */
    @Transactional
    fun markReadFromCrm(studioId: UUID, messageId: UUID) {
        val message = messageRepository.findByIdAndStudioId(messageId, studioId)
            ?: throw NotFoundException("Nie znaleziono wiadomości")
        if (message.isRead) return

        applyRead(message, CommReadSource.CRM)

        // The server learns about it asynchronously; the echo of our own STORE coming
        // back through sync is ignored because the message is already read locally.
        outboxRepository.save(
            CommOutboxEntity(
                id = UUID.randomUUID(),
                studioId = message.studioId,
                accountId = message.accountId,
                messageId = message.id,
                commandType = CommOutboxType.MARK_SEEN,
                status = CommOutboxStatus.PENDING,
                nextAttemptAt = Instant.now(),
                lastError = null
            )
        )
    }

    /** Whole-thread variant used when the user opens a conversation. */
    @Transactional
    fun markThreadReadFromCrm(studioId: UUID, threadId: UUID) {
        val thread = threadRepository.findByIdAndStudioId(threadId, studioId)
            ?: throw NotFoundException("Nie znaleziono wątku")
        if (thread.unreadCount == 0) return
        messageRepository.findByThreadIdOrderBySentAtAsc(thread.id)
            .filter { !it.isRead && it.direction == CommDirection.INBOUND }
            .forEach { markReadFromCrm(studioId, it.id) }
    }

    /** Sync engine saw the \Seen flag set by an external client. Idempotent. */
    @Transactional
    fun markReadFromServer(message: CommMessageEntity) {
        if (message.isRead) return
        applyRead(message, CommReadSource.EXTERNAL)
    }

    private fun applyRead(message: CommMessageEntity, source: CommReadSource) {
        message.isRead = true
        message.readSource = source
        message.readAt = Instant.now()
        messageRepository.save(message)

        threadRepository.findById(message.threadId).ifPresent { thread ->
            if (thread.unreadCount > 0) {
                thread.unreadCount -= 1
                threadRepository.save(thread)
            }
        }

        eventPublisher.publishEvent(
            CommMessageReadEvent(
                studioId = message.studioId,
                threadId = message.threadId,
                messageId = message.id,
                readSource = source
            )
        )
        log.debug("[COMMS] Message {} marked read ({})", message.id, source)
    }
}
