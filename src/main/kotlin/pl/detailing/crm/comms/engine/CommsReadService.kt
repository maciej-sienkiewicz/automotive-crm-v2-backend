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
import pl.detailing.crm.shared.ValidationException
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

    /**
     * „Oznacz jako nieprzeczytaną" z LISTY rozmów.
     *
     * Cofa dokładnie JEDNĄ wiadomość - najnowszą przychodzącą w wątku - a nie całą
     * rozmowę. Wątek z piętnastoma wiadomościami klienta po takim kliknięciu ma
     * wrócić z licznikiem „1", bo to jest to, co użytkownik chce sobie zostawić do
     * przeczytania; cofnięcie wszystkich piętnastu kazałoby mu je potem odklikiwać.
     *
     * Cała robota (flaga, licznik wątku, MARK_UNSEEN do serwera, zdarzenie) dzieje
     * się w [markUnreadFromCrm] - tu zostaje tylko wybór wiadomości.
     *
     * @return id cofniętej wiadomości albo null, gdy nie było czego cofać:
     *   wątek bez wiadomości przychodzących (sama korespondencja wychodząca) albo
     *   taki, w którym najnowsza przychodząca i tak jest już nieprzeczytana.
     */
    @Transactional
    fun markThreadUnreadFromCrm(studioId: UUID, threadId: UUID): UUID? {
        val newestInbound = messageRepository
            .findFirstByStudioIdAndThreadIdAndDirectionOrderBySentAtDesc(
                studioId, threadId, CommDirection.INBOUND
            )
            ?: return null
        if (!newestInbound.isRead) return null

        markUnreadFromCrm(studioId, newestInbound.id)
        return newestInbound.id
    }

    /**
     * Użytkownik oznaczył wiadomość jako NIEPRZECZYTANĄ w CRM. Odwrotność
     * [markReadFromCrm].
     *
     * To jedyne miejsce, w którym łamiemy monotoniczność „przeczytane już nie wraca"
     * (patrz [markReadFromServer]) — dlatego musi tu paść MARK_UNSEEN: bez wyczyszczenia
     * flagi \Seen na serwerze najbliższy reconcile zobaczyłby ją i cofnął oznaczenie.
     */
    @Transactional
    fun markUnreadFromCrm(studioId: UUID, messageId: UUID) {
        val message = messageRepository.findByIdAndStudioId(messageId, studioId)
            ?: throw NotFoundException("Nie znaleziono wiadomości")
        // Wiadomość wychodząca jest „przeczytana z definicji" i nie wchodzi do licznika
        // nieprzeczytanych wątku — oznaczanie jej jako nieprzeczytanej byłoby bez sensu.
        if (message.direction != CommDirection.INBOUND) {
            throw ValidationException("Tylko wiadomość przychodzącą można oznaczyć jako nieprzeczytaną")
        }
        if (!message.isRead) return

        message.isRead = false
        message.readSource = null
        message.readAt = null
        messageRepository.save(message)

        threadRepository.findById(message.threadId).ifPresent { thread ->
            thread.unreadCount += 1
            threadRepository.save(thread)
        }

        outboxRepository.save(
            CommOutboxEntity(
                id = UUID.randomUUID(),
                studioId = message.studioId,
                accountId = message.accountId,
                messageId = message.id,
                commandType = CommOutboxType.MARK_UNSEEN,
                status = CommOutboxStatus.PENDING,
                nextAttemptAt = Instant.now(),
                lastError = null
            )
        )

        // To samo zdarzenie co przy „przeczytano": front i tak tylko odświeża wątek
        // i listę, a źródłem prawdy jest baza (teraz: nieprzeczytana).
        eventPublisher.publishEvent(
            CommMessageReadEvent(
                studioId = message.studioId,
                threadId = message.threadId,
                messageId = message.id,
                readSource = CommReadSource.CRM
            )
        )
        log.debug("[COMMS] Message {} marked UNREAD (CRM)", message.id)
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
