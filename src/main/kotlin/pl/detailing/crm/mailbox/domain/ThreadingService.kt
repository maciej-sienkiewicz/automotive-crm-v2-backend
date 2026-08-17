package pl.detailing.crm.mailbox.domain

import org.springframework.stereotype.Service
import pl.detailing.crm.mailbox.infrastructure.MailMessageRepository
import pl.detailing.crm.mailbox.infrastructure.MailThreadEntity
import pl.detailing.crm.mailbox.infrastructure.MailThreadRepository
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Strips reply/forward prefixes so a conversation keeps one normalized subject even
 * after several rounds in mixed Polish/English mail clients.
 */
object SubjectNormalizer {

    private val replyPrefix = Regex(
        "^\\s*((re|odp|odpowiedz|fwd|fw|pd)\\s*(\\[\\d+])?\\s*:\\s*)+",
        RegexOption.IGNORE_CASE
    )

    fun normalize(subject: String?): String? {
        if (subject.isNullOrBlank()) return null
        var current = subject.trim()
        while (true) {
            val stripped = replyPrefix.replaceFirst(current, "").trim()
            if (stripped == current) break
            current = stripped
        }
        return current.lowercase().replace(Regex("\\s+"), " ").take(500).ifBlank { null }
    }
}

/**
 * Resolves which thread an incoming message belongs to.
 *
 * This replaces the "does this address have an open lead" heuristic: that one merged
 * unrelated inquiries from a returning client and lost replies once the lead was closed
 * or the 60-day window elapsed. Here the answer comes from the RFC 5322 headers the
 * client's mail program already sends, and only falls back to matching on subject when
 * those headers are absent.
 */
@Service
class ThreadingService(
    private val threadRepository: MailThreadRepository,
    private val messageRepository: MailMessageRepository
) {

    /**
     * Deterministic cascade — the first hit wins:
     * 1. In-Reply-To points at a message we already stored
     * 2. any References entry does, newest first
     * 3. the provider's own thread key (X-GM-THRID / Graph conversationId)
     * 4. same participant and normalized subject within the recency window
     * 5. otherwise a brand new thread
     */
    fun resolveThread(
        studioId: UUID,
        mailAccountId: UUID?,
        inReplyTo: String?,
        references: List<String>,
        externalThreadKey: String?,
        participantEmail: String,
        subject: String?,
        sentAt: Instant
    ): MailThreadEntity {
        inReplyTo?.let { candidate ->
            messageRepository.findByStudioIdAndMessageId(studioId, candidate)?.let { message ->
                threadRepository.findByIdAndStudioId(message.threadId, studioId)?.let { return it }
            }
        }

        for (reference in references.asReversed()) {
            messageRepository.findByStudioIdAndMessageId(studioId, reference)?.let { message ->
                threadRepository.findByIdAndStudioId(message.threadId, studioId)?.let { return it }
            }
        }

        externalThreadKey?.let { key ->
            threadRepository.findByStudioIdAndExternalThreadKey(studioId, key)?.let { return it }
        }

        val subjectNorm = SubjectNormalizer.normalize(subject)
        if (subjectNorm != null) {
            threadRepository.findRecentBySubjectAndParticipant(
                studioId = studioId,
                subjectNorm = subjectNorm,
                participant = participantEmail.lowercase(),
                since = sentAt.minus(SUBJECT_MATCH_WINDOW_DAYS, ChronoUnit.DAYS)
            ).firstOrNull()?.let { return it }
        }

        return threadRepository.save(
            MailThreadEntity(
                id = UUID.randomUUID(),
                studioId = studioId,
                mailAccountId = mailAccountId,
                leadId = null,
                subjectNorm = subjectNorm,
                externalThreadKey = externalThreadKey,
                classification = MailThreadClassification.PENDING,
                lastMessageAt = sentAt,
                lastDirection = null
            )
        )
    }

    /** Moves the thread's cursor forward; out-of-order deliveries must not rewind it. */
    fun touch(thread: MailThreadEntity, sentAt: Instant, direction: MailDirection): MailThreadEntity {
        if (!sentAt.isBefore(thread.lastMessageAt)) {
            thread.lastMessageAt = sentAt
            thread.lastDirection = direction
        }
        return threadRepository.save(thread)
    }

    companion object {
        const val SUBJECT_MATCH_WINDOW_DAYS = 30L

        /** Normalizes a raw Message-ID header: drops surrounding whitespace and angle brackets. */
        fun normalizeMessageId(raw: String?): String? =
            raw?.trim()?.removePrefix("<")?.removeSuffix(">")?.trim()?.take(500)?.ifBlank { null }

        /** Parses a References header, preserving order (oldest first). */
        fun parseReferences(raw: String?): List<String> {
            if (raw.isNullOrBlank()) return emptyList()
            return Regex("<([^<>]+)>").findAll(raw).map { it.groupValues[1].take(500) }.toList()
        }

        /**
         * Some senders (and the Cloudflare webhook payload) provide no Message-ID. A hash of the
         * envelope keeps re-delivery idempotent, which the unique index depends on.
         */
        fun syntheticMessageId(fromEmail: String, sentAt: Instant, subject: String?): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$fromEmail|${sentAt.toEpochMilli()}|${subject.orEmpty()}".toByteArray())
                .joinToString("") { "%02x".format(it) }
            return "synthetic-$digest@detailing.local"
        }
    }
}
