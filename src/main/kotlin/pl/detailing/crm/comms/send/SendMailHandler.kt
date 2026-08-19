package pl.detailing.crm.comms.send

import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import pl.detailing.crm.comms.domain.CommFolderKind
import pl.detailing.crm.comms.domain.CommOutboundSentEvent
import pl.detailing.crm.comms.domain.CommOutboxStatus
import pl.detailing.crm.comms.domain.CommOutboxType
import pl.detailing.crm.comms.domain.ParsedEmail
import pl.detailing.crm.comms.engine.CommsIngestService
import pl.detailing.crm.comms.infrastructure.CommMessageRepository
import pl.detailing.crm.comms.infrastructure.CommOutboxEntity
import pl.detailing.crm.comms.infrastructure.CommOutboxRepository
import pl.detailing.crm.comms.infrastructure.CommThreadRepository
import pl.detailing.crm.comms.infrastructure.EmailHtmlSanitizer
import pl.detailing.crm.comms.signature.UserMailSignatureService
import pl.detailing.crm.mailbox.infrastructure.MailAccountRepository
import pl.detailing.crm.mailbox.domain.MailAccountStatus
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.util.UUID

data class SendMailCommand(
    val studioId: StudioId,
    /** Author of the message — decides whose signature gets appended. */
    val userId: UserId,
    val accountId: UUID?,
    /** Reply into an existing conversation; null starts a new one. */
    val threadId: UUID?,
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val subject: String?,
    val bodyHtml: String,
    /** Append the author's saved signature. Composed here, not in the browser, so the
     *  stored signature stays the single source of truth for every client of this API. */
    val appendSignature: Boolean = false
)

data class SendMailResult(
    val messageId: UUID,
    val threadId: UUID
)

/**
 * Composing and replying. SMTP first (the user must know immediately when the server
 * rejects the mail), then the local copy is persisted through the regular ingest path
 * (same threading, same sanitising), and an APPEND to the server's Sent folder is
 * queued so the message shows up in the client's other mail apps too.
 */
@Service
class SendMailHandler(
    private val accountRepository: MailAccountRepository,
    private val threadRepository: CommThreadRepository,
    private val messageRepository: CommMessageRepository,
    private val outboxRepository: CommOutboxRepository,
    private val sender: AccountMailSender,
    private val sanitizer: EmailHtmlSanitizer,
    private val ingestService: CommsIngestService,
    private val signatureService: UserMailSignatureService,
    private val eventPublisher: ApplicationEventPublisher
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun handle(command: SendMailCommand): SendMailResult {
        if (command.to.isEmpty()) throw ValidationException("Podaj adresata wiadomości")
        if (command.bodyHtml.isBlank()) throw ValidationException("Treść wiadomości nie może być pusta")

        val thread = command.threadId?.let {
            threadRepository.findByIdAndStudioId(it, command.studioId.value)
                ?: throw NotFoundException("Nie znaleziono wątku")
        }

        val accountId = command.accountId ?: thread?.accountId
            ?: throw ValidationException("Wybierz skrzynkę, z której chcesz wysłać wiadomość")
        val account = accountRepository.findByIdAndStudioId(accountId, command.studioId.value)
            ?: throw NotFoundException("Nie znaleziono skrzynki")
        if (account.status == MailAccountStatus.DISABLED) {
            throw ValidationException("Ta skrzynka jest odłączona — połącz ją ponownie, aby wysyłać")
        }

        // Threading headers: reply chains onto the newest message of the conversation.
        val lastMessage = thread?.let { messageRepository.findFirstByThreadIdOrderBySentAtDesc(it.id) }
        val references = lastMessage?.let { last ->
            (last.referencesIds?.split(' ')?.filter { it.isNotBlank() } ?: emptyList()) + last.messageIdHdr
        } ?: emptyList()
        val subject = when {
            !command.subject.isNullOrBlank() -> command.subject
            thread?.subject != null ->
                if (thread.subject!!.startsWith("re:", ignoreCase = true)) thread.subject!!
                else "Re: ${thread.subject}"
            else -> throw ValidationException("Podaj temat wiadomości")
        }

        // User-typed HTML goes through the same sanitiser as foreign mail — the composer
        // output is trusted-ish, but defence in depth costs one call. The signature is
        // appended before sanitising, so both halves get the identical treatment.
        val composed = command.bodyHtml + signatureBlock(command)
        val safeHtml = sanitizer.sanitize(composed, UUID.randomUUID(), emptyMap())

        val messageIdHdr = sender.newMessageId(account)
        val mail = OutgoingMail(
            to = command.to.map { it.trim().lowercase() },
            cc = command.cc.map { it.trim().lowercase() },
            subject = subject,
            bodyHtml = safeHtml,
            inReplyTo = lastMessage?.messageIdHdr,
            references = references.takeLast(MAX_REFERENCES)
        )

        sender.send(account, messageIdHdr, mail)

        // Persist through the standard ingest path: identical threading and aggregates.
        val sentAt = Instant.now()
        ingestService.ingest(
            account = account,
            folderKind = CommFolderKind.SENT,
            parsed = ParsedEmail(
                messageId = messageIdHdr,
                inReplyTo = mail.inReplyTo,
                references = mail.references,
                fromEmail = account.emailAddress.lowercase(),
                fromName = null,
                toEmails = mail.to,
                ccEmails = mail.cc,
                subject = subject,
                sentAt = sentAt,
                bodyHtml = safeHtml,
                bodyText = null,
                attachments = emptyList(),
                imapUid = null,
                seen = true
            ),
            uidValidity = null
        )

        val saved = messageRepository.findByAccountIdAndMessageIdHdr(account.id, messageIdHdr)
            ?: error("Wysłana wiadomość nie została zapisana — messageId=$messageIdHdr")

        // The copy in the server's Sent folder arrives asynchronously; if the server
        // saved one itself (some do), the outbox processor detects it and skips APPEND.
        outboxRepository.save(
            CommOutboxEntity(
                id = UUID.randomUUID(),
                studioId = account.studioId,
                accountId = account.id,
                messageId = saved.id,
                commandType = CommOutboxType.APPEND_SENT,
                status = CommOutboxStatus.PENDING,
                nextAttemptAt = Instant.now().plusSeconds(APPEND_DELAY_SECONDS),
                lastError = null
            )
        )

        eventPublisher.publishEvent(
            CommOutboundSentEvent(studioId = account.studioId, threadId = saved.threadId, sentAt = sentAt)
        )

        log.info("[COMMS] Wiadomość wysłana i zapisana | thread={} message={}", saved.threadId, saved.id)
        return SendMailResult(messageId = saved.id, threadId = saved.threadId)
    }

    /**
     * Signature markup appended to the body. The `--` separator is the long-standing
     * convention every mail client understands as "signature starts here", which also
     * lets our own quote-stripping recognise it later.
     */
    private fun signatureBlock(command: SendMailCommand): String {
        if (!command.appendSignature) return ""
        val signature = signatureService.get(command.studioId, command.userId).bodyHtml
        if (signature.isNullOrBlank()) return ""
        return """<div class="crm-signature" style="margin-top:16px"><div>--</div>$signature</div>"""
    }

    companion object {
        private const val MAX_REFERENCES = 20

        /** Grace period: lets a server-saved Sent copy arrive first, so we never create doubles. */
        private const val APPEND_DELAY_SECONDS = 60L
    }
}
