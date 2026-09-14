package pl.detailing.crm.communication.queue

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.communication.OutboundMessageCategory
import pl.detailing.crm.email.provider.EmailAttachment
import pl.detailing.crm.shared.CommunicationChannel
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Wiadomość w kształcie, w jakim bramka ją przyjęła — zanim ktokolwiek dotknął adresata. */
data class OutboundMessageDraft(
    val studioId: UUID,
    val customerId: UUID?,
    val channel: CommunicationChannel,
    val category: OutboundMessageCategory,
    val recipient: String,
    val subject: String?,
    val body: String,
    val context: String,
    val attachments: List<EmailAttachment> = emptyList()
)

/** Odłożona wiadomość razem z załącznikami — to, co dispatcher podaje bramce do wysyłki. */
data class QueuedOutboundMessage(
    val entity: OutboundMessageEntity,
    val attachments: List<EmailAttachment>
) {
    val id: UUID get() = entity.id
}

/**
 * Trwała kolejka wiadomości czekających na godziny wysyłki.
 *
 * Każda operacja ma WŁASNĄ transakcję (REQUIRES_NEW). Odłożenie wiadomości ma być tak
 * samo nieodwracalne, jak wywołanie dostawcy, które zastępuje: wywołujący, który po
 * `sendSms` wycofa swoją transakcję, dziś i tak ma wysłanego SMS-a — kolejka nie może
 * zachowywać się inaczej, bo dziennik komunikacji (też REQUIRES_NEW) już wskazuje na
 * jej wiersz. Po stronie dispatchera osobne transakcje odgradzają zajęcie wiersza od
 * wywołania dostawcy: sieć nie trzyma otwartej transakcji bazodanowej.
 */
@Service
class OutboundMessageQueue(
    private val repository: OutboundMessageJpaRepository,
    private val attachmentRepository: OutboundMessageAttachmentJpaRepository,
    private val meterRegistry: MeterRegistry
) {
    private val logger = LoggerFactory.getLogger(OutboundMessageQueue::class.java)

    companion object {
        /** Ile prób zanim wiadomość zostaje FAILED. Błąd dostawcy bywa chwilowy; trzeci raz już nie. */
        const val MAX_ATTEMPTS = 3

        /** Odstęp między próbami — wystarczający, żeby przejściowa awaria SMSAPI/SMTP minęła. */
        val RETRY_BACKOFF: Duration = Duration.ofMinutes(5)

        /** SENDING starsze niż to = restart w trakcie wysyłki; patrz [OutboundMessageStatus.SENDING]. */
        val STALE_SENDING_AFTER: Duration = Duration.ofMinutes(15)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun enqueue(draft: OutboundMessageDraft, scheduledFor: Instant, now: Instant = Instant.now()): OutboundMessageEntity {
        val entity = repository.save(
            OutboundMessageEntity(
                id = UUID.randomUUID(),
                studioId = draft.studioId,
                customerId = draft.customerId,
                channel = draft.channel,
                category = draft.category,
                recipient = draft.recipient,
                subject = draft.subject,
                body = draft.body,
                context = draft.context,
                status = OutboundMessageStatus.QUEUED,
                scheduledFor = scheduledFor,
                createdAt = now,
                updatedAt = now
            )
        )
        draft.attachments.forEach { attachment ->
            attachmentRepository.save(
                OutboundMessageAttachmentEntity(
                    id = UUID.randomUUID(),
                    messageId = entity.id,
                    fileName = attachment.fileName,
                    contentType = attachment.contentType,
                    content = attachment.content
                )
            )
        }
        meterRegistry.counter("communication.queued", "channel", draft.channel.name, "category", draft.category.name).increment()
        logger.info(
            "Outbound {} queued for send window | id={} studio={} category={} scheduledFor={} context={}",
            draft.channel, entity.id, draft.studioId, draft.category, scheduledFor, draft.context
        )
        return entity
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    fun dueIds(now: Instant, limit: Int): List<UUID> =
        repository.findDueIds(OutboundMessageStatus.QUEUED, now, PageRequest.of(0, limit))

    /**
     * Zajmuje wiadomość do wysyłki. Null, gdy ktoś inny zdążył pierwszy albo wiadomość
     * została w międzyczasie anulowana — wtedy nie ma czego wysyłać.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claim(id: UUID, now: Instant): QueuedOutboundMessage? {
        val claimed = repository.transition(id, OutboundMessageStatus.QUEUED, OutboundMessageStatus.SENDING, now)
        if (claimed == 0) return null
        val entity = repository.findById(id).orElse(null) ?: return null
        val attachments = attachmentRepository.findAllByMessageId(id).map {
            EmailAttachment(fileName = it.fileName, content = it.content, contentType = it.contentType)
        }
        return QueuedOutboundMessage(entity, attachments)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markSent(id: UUID, externalMessageId: String?, now: Instant) {
        val entity = repository.findById(id).orElse(null) ?: return
        entity.status = OutboundMessageStatus.SENT
        entity.attempts += 1
        entity.externalMessageId = externalMessageId
        entity.lastError = null
        entity.sentAt = now
        entity.updatedAt = now
        repository.save(entity)
        // Bajty załącznika są potrzebne tylko do wysyłki; po niej to martwy balast w bazie.
        attachmentRepository.deleteAll(attachmentRepository.findAllByMessageId(id))
        meterRegistry.counter("communication.queue.sent", "channel", entity.channel.name).increment()
    }

    /**
     * Nieudana próba. Wraca do QUEUED z terminem po [RETRY_BACKOFF] (dociągniętym do okna
     * przez wywołującego), albo — po [MAX_ATTEMPTS] — zostaje FAILED z ostatnim błędem.
     * [retryable] = false (brak kredytów, wiadomość bez sensu) kończy od razu.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun markAttemptFailed(id: UUID, error: String?, retryable: Boolean, nextAttemptAt: Instant, now: Instant): OutboundMessageStatus? {
        val entity = repository.findById(id).orElse(null) ?: return null
        entity.attempts += 1
        entity.lastError = error
        entity.updatedAt = now
        if (retryable && entity.attempts < MAX_ATTEMPTS) {
            entity.status = OutboundMessageStatus.QUEUED
            entity.scheduledFor = nextAttemptAt
            logger.warn(
                "Outbound {} attempt {}/{} failed, retry at {} | id={} error={}",
                entity.channel, entity.attempts, MAX_ATTEMPTS, nextAttemptAt, id, error
            )
        } else {
            entity.status = OutboundMessageStatus.FAILED
            attachmentRepository.deleteAll(attachmentRepository.findAllByMessageId(id))
            meterRegistry.counter("communication.queue.failed", "channel", entity.channel.name).increment()
            logger.warn(
                "Outbound {} FAILED after {} attempt(s) | id={} studio={} error={}",
                entity.channel, entity.attempts, id, entity.studioId, error
            )
        }
        repository.save(entity)
        return entity.status
    }

    /**
     * Sprząta po restarcie w trakcie wysyłki. Nie ponawia — patrz [OutboundMessageStatus.SENDING].
     * Zwraca id oznaczonych wierszy, żeby dispatcher mógł domknąć wpisy w dzienniku komunikacji.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun failStaleSending(now: Instant): List<UUID> {
        val stale = repository.findByStatusUpdatedBefore(OutboundMessageStatus.SENDING, now.minus(STALE_SENDING_AFTER))
        stale.forEach { entity ->
            val stuckSince = entity.updatedAt
            entity.status = OutboundMessageStatus.FAILED
            entity.lastError = "Wysyłka przerwana (restart aplikacji w trakcie wysyłki)"
            entity.updatedAt = now
            repository.save(entity)
            attachmentRepository.deleteAll(attachmentRepository.findAllByMessageId(entity.id))
            logger.error("Outbound {} stuck in SENDING since {} — marked FAILED | id={}", entity.channel, stuckSince, entity.id)
        }
        return stale.map { it.id }
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    fun queuedCount(): Long = repository.countByStatus(OutboundMessageStatus.QUEUED)
}
