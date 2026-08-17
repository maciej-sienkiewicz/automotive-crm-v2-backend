package pl.detailing.crm.mailbox.thread

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.mailbox.domain.MailThreadClassification
import pl.detailing.crm.mailbox.infrastructure.MailMessageRepository
import pl.detailing.crm.mailbox.infrastructure.MailSenderRuleEntity
import pl.detailing.crm.mailbox.infrastructure.MailSenderRuleRepository
import pl.detailing.crm.mailbox.infrastructure.MailThreadRepository
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.util.UUID

data class GetLeadThreadQuery(val leadId: UUID, val studioId: StudioId)

data class ThreadMessageView(
    val id: UUID,
    val direction: String,
    val fromEmail: String,
    val fromName: String?,
    val subject: String?,
    val sentAt: Instant,
    val bodyText: String,
    val hasAttachments: Boolean,
    val sendStatus: String
)

data class LeadThreadView(
    /** Null when the lead has no e-mail conversation behind it (manual lead, or created before the mailbox module). */
    val threadId: UUID?,
    val leadId: UUID?,
    val classification: String?,
    val lastMessageAt: Instant?,
    val lastDirection: String?,
    val canReplyFromCrm: Boolean,
    val messages: List<ThreadMessageView>
)

private val EMPTY_THREAD = LeadThreadView(
    threadId = null,
    leadId = null,
    classification = null,
    lastMessageAt = null,
    lastDirection = null,
    canReplyFromCrm = false,
    messages = emptyList()
)

/**
 * Returns the conversation behind a lead. Bodies are the denoised ones, so the client sees
 * only what each message actually added rather than the whole quoted history again.
 */
@Service
class GetLeadThreadHandler(
    private val threadRepository: MailThreadRepository,
    private val messageRepository: MailMessageRepository
) {
    @Transactional(readOnly = true)
    suspend fun handle(query: GetLeadThreadQuery): LeadThreadView = withContext(Dispatchers.IO) {
        val thread = threadRepository.findByLeadId(query.leadId)
            ?.takeIf { it.studioId == query.studioId.value }
            ?: return@withContext EMPTY_THREAD

        val messages = messageRepository.findByThreadIdOrderBySentAtAsc(thread.id).map {
            ThreadMessageView(
                id = it.id,
                direction = it.direction.name,
                fromEmail = it.fromEmail,
                fromName = it.fromName,
                subject = it.subject,
                sentAt = it.sentAt,
                bodyText = it.bodyTextClean.orEmpty(),
                hasAttachments = it.hasAttachments,
                sendStatus = it.sendStatus.name
            )
        }

        LeadThreadView(
            threadId = thread.id,
            leadId = thread.leadId,
            classification = thread.classification.name,
            lastMessageAt = thread.lastMessageAt,
            lastDirection = thread.lastDirection?.name,
            // Replying needs a mailbox behind the thread; webhook-only threads have none.
            canReplyFromCrm = thread.mailAccountId != null,
            messages = messages
        )
    }
}

data class ReviewQueueItem(
    val threadId: UUID,
    val fromEmail: String,
    val fromName: String?,
    val subject: String?,
    val snippet: String,
    val receivedAt: Instant
)

/**
 * Threads the funnel could not decide on. Nothing is silently dropped: an unsure message
 * waits here for a one-click human verdict rather than disappearing.
 */
@Service
class GetReviewQueueHandler(
    private val threadRepository: MailThreadRepository,
    private val messageRepository: MailMessageRepository
) {
    @Transactional(readOnly = true)
    suspend fun handle(studioId: StudioId): List<ReviewQueueItem> = withContext(Dispatchers.IO) {
        threadRepository
            .findByStudioIdAndClassificationOrderByLastMessageAtDesc(
                studioId.value,
                MailThreadClassification.UNSURE
            )
            .mapNotNull { thread ->
                val first = messageRepository.findFirstByThreadIdOrderBySentAtAsc(thread.id)
                    ?: return@mapNotNull null
                ReviewQueueItem(
                    threadId = thread.id,
                    fromEmail = first.fromEmail,
                    fromName = first.fromName,
                    subject = first.subject,
                    snippet = first.bodyTextClean.orEmpty().take(SNIPPET_LENGTH),
                    receivedAt = first.sentAt
                )
            }
    }

    companion object {
        private const val SNIPPET_LENGTH = 200
    }
}

data class ReviewDecisionCommand(
    val threadId: UUID,
    val studioId: StudioId,
    /** INQUIRY or IGNORE_SENDER. */
    val decision: String
)

/**
 * Records the human verdict and remembers the sender, so the same newsletter or accountant
 * never reaches the queue twice. This is how the mailbox gets quieter with use.
 */
@Service
class ApplyReviewDecisionHandler(
    private val threadRepository: MailThreadRepository,
    private val messageRepository: MailMessageRepository,
    private val senderRuleRepository: MailSenderRuleRepository
) {
    @Transactional
    suspend fun handle(command: ReviewDecisionCommand) = withContext(Dispatchers.IO) {
        val thread = threadRepository.findByIdAndStudioId(command.threadId, command.studioId.value)
            ?: throw NotFoundException("Nie znaleziono wątku wiadomości")
        val first = messageRepository.findFirstByThreadIdOrderBySentAtAsc(command.threadId)

        when (command.decision.uppercase()) {
            "INQUIRY" -> thread.classification = MailThreadClassification.INQUIRY
            "IGNORE_SENDER" -> {
                thread.classification = MailThreadClassification.OTHER
                first?.let { rememberSender(command.studioId, it.fromEmail, MailThreadClassification.OTHER) }
            }

            else -> throw ValidationException("Nieznana decyzja: ${command.decision}")
        }
        threadRepository.save(thread)
        Unit
    }

    private fun rememberSender(studioId: StudioId, email: String, classification: MailThreadClassification) {
        val pattern = email.lowercase()
        if (senderRuleRepository.findByStudioIdAndPatternIn(studioId.value, listOf(pattern)).isNotEmpty()) return
        senderRuleRepository.save(
            MailSenderRuleEntity(
                id = UUID.randomUUID(),
                studioId = studioId.value,
                pattern = pattern,
                classification = classification
            )
        )
    }
}
