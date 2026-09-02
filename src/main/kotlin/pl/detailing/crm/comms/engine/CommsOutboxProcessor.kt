package pl.detailing.crm.comms.engine

import com.sun.mail.imap.IMAPFolder
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Store
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import pl.detailing.crm.comms.domain.CommOutboxStatus
import pl.detailing.crm.comms.domain.CommOutboxType
import pl.detailing.crm.comms.infrastructure.CommAttachmentRepository
import pl.detailing.crm.comms.infrastructure.CommMessageEntity
import pl.detailing.crm.comms.infrastructure.CommMessageRepository
import pl.detailing.crm.comms.infrastructure.CommOutboxEntity
import pl.detailing.crm.comms.infrastructure.CommOutboxRepository
import pl.detailing.crm.comms.send.AccountMailSender
import pl.detailing.crm.comms.send.OutgoingAttachment
import pl.detailing.crm.comms.send.OutgoingMail
import pl.detailing.crm.mailbox.infrastructure.MailAccountEntity
import pl.detailing.crm.mailbox.infrastructure.MailAccountRepository
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.min
import kotlin.math.pow

/**
 * Executes the queued IMAP side effects of user actions. Every command is idempotent:
 * setting \Seen on an already-seen message is a no-op, and APPEND checks whether the
 * server already holds the message before writing. Failures back off exponentially and
 * never block the queue for other accounts.
 */
@Service
class CommsOutboxProcessor(
    private val outboxRepository: CommOutboxRepository,
    private val messageRepository: CommMessageRepository,
    private val attachmentRepository: CommAttachmentRepository,
    private val accountRepository: MailAccountRepository,
    private val imapSessions: ImapSessions,
    private val sender: AccountMailSender
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${comms.outbox.interval-ms:30000}", initialDelay = 45_000)
    fun processDue() {
        val due = outboxRepository.findDue(Instant.now(), PageRequest.of(0, BATCH_SIZE))
        if (due.isEmpty()) return

        // One IMAP session per account per run, not per command.
        due.groupBy { it.accountId }.forEach { (accountId, commands) ->
            val account = accountRepository.findById(accountId).orElse(null)
            if (account == null) {
                commands.forEach { fail(it, "Konto pocztowe nie istnieje", terminal = true) }
                return@forEach
            }
            var store: Store? = null
            try {
                store = imapSessions.openStore(account)
                commands.forEach { command -> execute(store, account, command) }
            } catch (ex: Exception) {
                log.warn("[OUTBOX] Nie można otworzyć IMAP dla {}: {}", account.emailAddress, ex.message)
                commands.forEach { fail(it, ex.message ?: "Błąd połączenia IMAP", terminal = false) }
            } finally {
                runCatching { store?.close() }
            }
        }
    }

    /** Housekeeping: processed commands are useless after a day. */
    @Scheduled(cron = "0 20 3 * * *")
    fun cleanup() {
        val removed = outboxRepository.deleteOldByStatus(
            CommOutboxStatus.DONE, Instant.now().minus(1, ChronoUnit.DAYS)
        )
        if (removed > 0) log.debug("[OUTBOX] Usunięto {} wykonanych komend", removed)
    }

    private fun execute(store: Store, account: MailAccountEntity, command: CommOutboxEntity) {
        val message = messageRepository.findById(command.messageId).orElse(null)
        if (message == null) {
            fail(command, "Wiadomość nie istnieje", terminal = true)
            return
        }
        try {
            when (command.commandType) {
                CommOutboxType.MARK_SEEN -> markSeen(store, message)
                CommOutboxType.APPEND_SENT -> appendSent(store, account, message)
            }
            command.status = CommOutboxStatus.DONE
            command.lastError = null
            outboxRepository.save(command)
        } catch (ex: Exception) {
            fail(command, ex.message ?: ex.javaClass.simpleName, terminal = false)
        }
    }

    private fun markSeen(store: Store, message: CommMessageEntity) {
        val uid = message.imapUid ?: return // never had an IMAP identity — nothing to flag
        val folder = store.getFolder("INBOX") as IMAPFolder
        folder.open(Folder.READ_WRITE)
        try {
            // UIDVALIDITY changed since we stored the uid → the uid points at nothing we
            // know. \Seen is cosmetic on the server side, so this is a silent success.
            if (message.imapUidValidity != null && folder.uidValidity != message.imapUidValidity) return
            val imapMessage = folder.getMessageByUID(uid) ?: return
            imapMessage.setFlag(Flags.Flag.SEEN, true)
        } finally {
            runCatching { folder.close(false) }
        }
    }

    private fun appendSent(store: Store, account: MailAccountEntity, message: CommMessageEntity) {
        // The server already holds this message (it saved its own Sent copy, which our
        // sync ingested and stamped with a uid) — appending again would create a double.
        val fresh = messageRepository.findById(message.id).orElse(null) ?: return
        if (fresh.imapUid != null) return

        val sentFolderName = imapSessions.findSentFolderName(store)
            ?: return // no recognisable Sent folder — mail was sent, copy is only local
        val folder = store.getFolder(sentFolderName)
        folder.open(Folder.READ_WRITE)
        try {
            val mime = sender.compose(
                account,
                fresh.messageIdHdr,
                OutgoingMail(
                    to = fresh.toEmails?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
                    cc = fresh.ccEmails?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList(),
                    subject = fresh.subject ?: "",
                    bodyHtml = fresh.bodyHtmlSafe ?: "",
                    inReplyTo = fresh.inReplyTo,
                    references = fresh.referencesIds?.split(' ')?.filter { it.isNotBlank() } ?: emptyList(),
                    // Kopia w Wysłanych ma nieść te same pliki, które dostał odbiorca —
                    // inaczej z telefonu nie da się sprawdzić, co właściwie poszło.
                    attachments = attachmentRepository.findByMessageId(fresh.id)
                        .filter { !it.isInline }
                        .map { OutgoingAttachment(it.fileName, it.contentType, it.content) }
                )
            )
            mime.setFlag(Flags.Flag.SEEN, true)
            folder.appendMessages(arrayOf(mime))
            log.debug("[OUTBOX] APPEND do '{}' wykonany dla {}", sentFolderName, fresh.messageIdHdr)
        } finally {
            runCatching { folder.close(false) }
        }
    }

    private fun fail(command: CommOutboxEntity, error: String, terminal: Boolean) {
        command.attempts += 1
        command.lastError = error.take(500)
        if (terminal || command.attempts >= MAX_ATTEMPTS) {
            command.status = CommOutboxStatus.FAILED
        } else {
            val backoffSeconds = min(
                BASE_BACKOFF_SECONDS * 2.0.pow(command.attempts).toLong(),
                MAX_BACKOFF_SECONDS
            )
            command.nextAttemptAt = Instant.now().plusSeconds(backoffSeconds)
        }
        outboxRepository.save(command)
    }

    companion object {
        private const val BATCH_SIZE = 200
        private const val MAX_ATTEMPTS = 10
        private const val BASE_BACKOFF_SECONDS = 30L
        private const val MAX_BACKOFF_SECONDS = 3600L
    }
}
