package pl.detailing.crm.mailbox.reply

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.mailbox.domain.MailAccountStatus
import pl.detailing.crm.mailbox.domain.MailDirection
import pl.detailing.crm.mailbox.domain.MailSendStatus
import pl.detailing.crm.mailbox.domain.MailThreadClassification
import pl.detailing.crm.mailbox.infrastructure.MailAccountRepository
import pl.detailing.crm.mailbox.infrastructure.MailMessageEntity
import pl.detailing.crm.mailbox.infrastructure.MailMessageRepository
import pl.detailing.crm.mailbox.infrastructure.MailThreadRepository
import pl.detailing.crm.mailbox.infrastructure.OutgoingReply
import pl.detailing.crm.mailbox.infrastructure.ThreadAwareMailSender
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.util.UUID

data class SendThreadReplyCommand(
    val threadId: UUID,
    val studioId: StudioId,
    val userId: UserId,
    val userName: String,
    val bodyHtml: String
)

/**
 * Sends a reply from the studio's own mailbox and, crucially, owns the threading headers.
 *
 * The Message-ID is generated and persisted *before* the SMTP call (a transactional outbox):
 * a retry reuses the same id instead of sending twice, and the copy that later comes back
 * from the Sent folder is recognised as already stored. Because we set In-Reply-To and
 * References, the client's answer carries our id back and links to this thread with no
 * guessing at all.
 */
@Service
class SendThreadReplyHandler(
    private val threadRepository: MailThreadRepository,
    private val messageRepository: MailMessageRepository,
    private val accountRepository: MailAccountRepository,
    private val mailSender: ThreadAwareMailSender
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    suspend fun handle(command: SendThreadReplyCommand): SendThreadReplyResult = withContext(Dispatchers.IO) {
        val thread = threadRepository.findByIdAndStudioId(command.threadId, command.studioId.value)
            ?: throw NotFoundException("Nie znaleziono wątku wiadomości")

        val accountId = thread.mailAccountId
            ?: throw ValidationException(
                "Ten wątek przyszedł przez przekierowanie e-mail — podłącz skrzynkę studia, aby odpowiadać z CRM"
            )
        val account = accountRepository.findByIdAndStudioId(accountId, command.studioId.value)
            ?: throw NotFoundException("Nie znaleziono skrzynki przypisanej do wątku")
        if (account.status != MailAccountStatus.ACTIVE) {
            throw ValidationException("Skrzynka ${account.emailAddress} jest rozłączona — połącz ją ponownie")
        }

        val messages = messageRepository.findByThreadIdOrderBySentAtAsc(command.threadId)
        val lastMessage = messages.lastOrNull()
            ?: throw ValidationException("Wątek nie zawiera żadnej wiadomości")

        val recipient = resolveRecipient(messages.map { it.fromEmail to it.direction }, account.emailAddress)
            ?: throw ValidationException("Nie udało się ustalić adresu odbiorcy dla tego wątku")

        val baseSubject = messages.firstOrNull { it.subject != null }?.subject ?: "Zapytanie"
        val subject = if (baseSubject.startsWith("Re:", ignoreCase = true)) baseSubject else "Re: $baseSubject"
        val references = messages.map { it.messageId }.distinct().takeLast(MAX_REFERENCES)

        val messageId = mailSender.newMessageId(account)
        val sentAt = Instant.now()

        val outbound = messageRepository.save(
            MailMessageEntity(
                id = UUID.randomUUID(),
                studioId = command.studioId.value,
                threadId = command.threadId,
                mailAccountId = accountId,
                direction = MailDirection.OUTBOUND,
                messageId = messageId,
                inReplyTo = lastMessage.messageId,
                referencesIds = references.joinToString(" ").ifBlank { null },
                fromEmail = account.emailAddress,
                fromName = command.userName,
                toEmails = recipient,
                subject = subject,
                sentAt = sentAt,
                bodyHtml = command.bodyHtml,
                bodyTextClean = command.bodyHtml.replace(Regex("<[^>]+>"), " ")
                    .replace(Regex("\\s+"), " ").trim().take(4000),
                hasAttachments = false,
                providerUid = null,
                sendStatus = MailSendStatus.OUTBOX_PENDING
            )
        )

        try {
            mailSender.send(
                account = account,
                messageId = messageId,
                reply = OutgoingReply(
                    to = recipient,
                    subject = subject,
                    bodyHtml = command.bodyHtml,
                    inReplyTo = lastMessage.messageId,
                    references = references
                )
            )
        } catch (e: Exception) {
            outbound.sendStatus = MailSendStatus.FAILED
            messageRepository.save(outbound)
            log.error("[MAILBOX] Reply send failed for thread {}: {}", command.threadId, e.message)
            throw ValidationException("Nie udało się wysłać wiadomości: ${e.message}")
        }

        outbound.sendStatus = MailSendStatus.SENT
        messageRepository.save(outbound)

        if (thread.classification == MailThreadClassification.PENDING ||
            thread.classification == MailThreadClassification.UNSURE
        ) {
            thread.classification = MailThreadClassification.INQUIRY
        }
        thread.lastMessageAt = sentAt
        thread.lastDirection = MailDirection.OUTBOUND
        threadRepository.save(thread)

        log.info("[MAILBOX] Reply <{}> sent on thread {} by {}", messageId, command.threadId, command.userId)
        SendThreadReplyResult(messageId = outbound.id, leadId = thread.leadId)
    }

    /** The counterparty is the sender of the last inbound message, falling back to any non-self address. */
    private fun resolveRecipient(
        participants: List<Pair<String, MailDirection>>,
        selfAddress: String
    ): String? = participants.lastOrNull { it.second == MailDirection.INBOUND }?.first
        ?: participants.map { it.first }.lastOrNull { !it.equals(selfAddress, ignoreCase = true) }

    companion object {
        /** References grows unbounded otherwise; mail servers cap header length. */
        private const val MAX_REFERENCES = 10
    }
}

data class SendThreadReplyResult(
    val messageId: UUID,
    val leadId: UUID?
)
