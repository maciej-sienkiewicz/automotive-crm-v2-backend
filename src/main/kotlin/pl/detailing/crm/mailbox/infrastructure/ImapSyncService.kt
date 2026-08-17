package pl.detailing.crm.mailbox.infrastructure

import jakarta.mail.AuthenticationFailedException
import jakarta.mail.Folder
import jakarta.mail.Message
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.UIDFolder
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeUtility
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import pl.detailing.crm.mailbox.domain.MailAccountStatus
import pl.detailing.crm.mailbox.domain.MailDirection
import pl.detailing.crm.mailbox.domain.MailIngestPort
import pl.detailing.crm.mailbox.domain.MailProviderType
import pl.detailing.crm.mailbox.domain.RawIncomingEmail
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Properties

/**
 * Polls IMAP mailboxes and hands every new message to [MailIngestPort]. INBOX gives us the
 * client's messages; the Sent folder is synced too, because studio owners routinely answer from
 * their phone outside the CRM and those replies must still land in the thread.
 *
 * One IMAP session per account, opened and closed per run — cheap Polish hosting providers
 * cap the number of concurrent IMAP sessions per mailbox, so pooling is counterproductive here.
 */
@Service
class ImapSyncService(
    private val accountRepository: MailAccountRepository,
    private val encryptionService: MailboxEncryptionService,
    private val ingestPort: MailIngestPort
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    fun syncAllAccounts() {
        val accounts = accountRepository.findByStatusAndProviderType(
            MailAccountStatus.ACTIVE,
            MailProviderType.IMAP_SMTP
        )
        if (accounts.isEmpty()) return

        log.debug("Rozpoczynam synchronizację {} skrzynek IMAP", accounts.size)
        accounts.forEach { account ->
            // One broken mailbox must never stop the others.
            try {
                syncAccount(account)
            } catch (ex: Exception) {
                log.error("Synchronizacja skrzynki {} nieudana: {}", account.emailAddress, ex.message, ex)
            }
        }
    }

    fun syncAccount(account: MailAccountEntity) {
        val imapHost = account.imapHost?.takeIf { it.isNotBlank() }
            ?: throw ValidationException("Skrzynka ${account.emailAddress} nie ma skonfigurowanego serwera IMAP")
        val imapPort = account.imapPort ?: 993
        val password = account.encryptedPassword?.let { encryptionService.decrypt(it) }
            ?: throw ValidationException("Skrzynka ${account.emailAddress} nie ma zapisanych poświadczeń")

        var store: Store? = null
        try {
            store = openStore(imapHost, imapPort, account.emailAddress, password)

            syncFolder(
                store = store,
                account = account,
                folderName = "INBOX",
                direction = MailDirection.INBOUND,
                savedUidValidity = account.inboxUidValidity,
                savedLastUid = account.inboxLastUid
            )?.let { (validity, lastUid) ->
                account.inboxUidValidity = validity
                account.inboxLastUid = lastUid
            }

            val sentFolderName = findSentFolder(store)
            if (sentFolderName == null) {
                log.debug("Skrzynka {} nie ma rozpoznanego folderu Wysłane", account.emailAddress)
            } else {
                syncFolder(
                    store = store,
                    account = account,
                    folderName = sentFolderName,
                    direction = MailDirection.OUTBOUND,
                    savedUidValidity = account.sentUidValidity,
                    savedLastUid = account.sentLastUid
                )?.let { (validity, lastUid) ->
                    account.sentUidValidity = validity
                    account.sentLastUid = lastUid
                }
            }

            account.status = MailAccountStatus.ACTIVE
            account.lastError = null
            account.lastSyncAt = Instant.now()
        } catch (ex: AuthenticationFailedException) {
            // Expected failure mode after a password change — surface it in the UI, do not spam the logs.
            account.status = MailAccountStatus.AUTH_FAILED
            account.lastError = "Logowanie do skrzynki odrzucone — połącz konto ponownie"
            log.warn("Uwierzytelnienie IMAP odrzucone dla {}", account.emailAddress)
        } catch (ex: Exception) {
            account.lastError = (ex.message ?: ex.javaClass.simpleName).take(500)
            throw ex
        } finally {
            account.updatedAt = Instant.now()
            accountRepository.save(account)
            runCatching { store?.close() }
        }
    }

    private fun openStore(host: String, port: Int, user: String, password: String): Store {
        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", host)
            put("mail.imaps.port", port.toString())
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.connectiontimeout", "10000")
            put("mail.imaps.timeout", "30000")
            put("mail.imaps.partialfetch", "false")
        }
        val session = Session.getInstance(props)
        val store = session.getStore("imaps")
        store.connect(host, port, user, password)
        return store
    }

    private fun findSentFolder(store: Store): String? = SENT_FOLDER_NAMES.firstOrNull { name ->
        runCatching { store.getFolder(name)?.exists() == true }.getOrDefault(false)
    }

    /** Returns the new (uidValidity, lastUid) pair, or null when the folder could not be read. */
    private fun syncFolder(
        store: Store,
        account: MailAccountEntity,
        folderName: String,
        direction: MailDirection,
        savedUidValidity: Long?,
        savedLastUid: Long?
    ): Pair<Long, Long>? {
        val folder = runCatching { store.getFolder(folderName) }.getOrNull() ?: return null
        if (!runCatching { folder.exists() }.getOrDefault(false)) return null

        folder.open(Folder.READ_ONLY)
        try {
            val uidFolder = folder as? UIDFolder ?: run {
                log.warn("Folder {} skrzynki {} nie obsługuje UID", folderName, account.emailAddress)
                return null
            }
            val uidValidity = uidFolder.uidValidity
            // UIDVALIDITY change means the server renumbered everything: our stored UIDs are meaningless.
            val fullResync = savedUidValidity == null || savedUidValidity != uidValidity
            val startUid = if (fullResync) 1L else (savedLastUid ?: 0L) + 1
            val isInitialSync = startUid <= 1L
            val cutoff = Instant.now().minus(INITIAL_SYNC_WINDOW_DAYS, ChronoUnit.DAYS)

            val messages = uidFolder.getMessagesByUID(startUid, UIDFolder.LASTUID) ?: emptyArray()
            var maxUid = savedLastUid?.takeIf { !fullResync } ?: 0L

            for (message in messages) {
                val uid = runCatching { uidFolder.getUID(message) }.getOrDefault(-1L)
                if (uid <= 0) continue
                // getMessagesByUID(start, LASTUID) always returns the last message, even below start.
                if (uid < startUid) continue

                try {
                    val mime = message as? MimeMessage ?: continue
                    val raw = toRawEmail(mime, direction, uid)
                    if (isInitialSync && raw.sentAt.isBefore(cutoff)) {
                        if (uid > maxUid) maxUid = uid
                        continue
                    }
                    runBlocking { ingestPort.ingest(account.studioId, account.id, raw) }
                } catch (ex: Exception) {
                    // A single unparseable message must not block the rest of the folder.
                    log.warn(
                        "Pominięto wiadomość uid={} w folderze {} skrzynki {}: {}",
                        uid, folderName, account.emailAddress, ex.message
                    )
                }
                if (uid > maxUid) maxUid = uid
            }
            return uidValidity to maxUid
        } finally {
            runCatching { folder.close(false) }
        }
    }

    private fun toRawEmail(message: MimeMessage, direction: MailDirection, uid: Long): RawIncomingEmail {
        val from = (runCatching { message.from?.firstOrNull() }.getOrNull() as? InternetAddress)
        val recipients = runCatching { message.getRecipients(Message.RecipientType.TO) }.getOrNull()
            ?.filterIsInstance<InternetAddress>()
            ?.mapNotNull { it.address?.lowercase() }
            ?: emptyList()

        val sentAt = (message.sentDate ?: message.receivedDate)?.toInstant() ?: Instant.now()
        val body = extractBody(message)

        return RawIncomingEmail(
            messageId = normalizeId(runCatching { message.messageID }.getOrNull()),
            inReplyTo = normalizeId(firstHeader(message, "In-Reply-To")),
            references = parseReferences(firstHeader(message, "References")),
            fromEmail = from?.address?.lowercase() ?: "unknown@unknown",
            fromName = from?.personal?.let { personal -> runCatching { MimeUtility.decodeText(personal) }.getOrDefault(personal) },
            toEmails = recipients,
            subject = runCatching { message.subject }.getOrNull(),
            sentAt = sentAt,
            bodyHtml = body.html,
            bodyText = body.text,
            hasAttachments = body.hasAttachments,
            providerUid = uid.toString(),
            direction = direction,
            headers = extractFilterHeaders(message)
        )
    }

    private fun firstHeader(message: MimeMessage, name: String): String? =
        runCatching { message.getHeader(name)?.firstOrNull() }.getOrNull()

    private fun normalizeId(value: String?): String? =
        value?.replace("<", "")?.replace(">", "")?.filterNot { it.isWhitespace() }?.takeIf { it.isNotBlank() }

    private fun parseReferences(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return REFERENCE_REGEX.findAll(value)
            .map { it.groupValues[1].filterNot { ch -> ch.isWhitespace() } }
            .filter { it.isNotBlank() }
            .toList()
    }

    private fun extractFilterHeaders(message: MimeMessage): Map<String, String> =
        FILTER_HEADERS.mapNotNull { name ->
            firstHeader(message, name)?.takeIf { it.isNotBlank() }?.let { name.lowercase() to it.trim() }
        }.toMap()

    private class BodyParts(var text: String? = null, var html: String? = null, var hasAttachments: Boolean = false)

    private fun extractBody(part: Part): BodyParts {
        val acc = BodyParts()
        walk(part, acc)
        return acc
    }

    private fun walk(part: Part, acc: BodyParts) {
        val disposition = runCatching { part.disposition }.getOrNull()
        if (Part.ATTACHMENT.equals(disposition, ignoreCase = true)) {
            acc.hasAttachments = true
            return
        }

        val content = runCatching { part.content }.getOrNull()
        if (content is Multipart) {
            for (i in 0 until content.count) {
                runCatching { walk(content.getBodyPart(i), acc) }
            }
            return
        }

        when {
            runCatching { part.isMimeType("text/plain") }.getOrDefault(false) ->
                if (acc.text == null) acc.text = content as? String
            runCatching { part.isMimeType("text/html") }.getOrDefault(false) ->
                if (acc.html == null) acc.html = content as? String
        }
    }

    private companion object {
        const val INITIAL_SYNC_WINDOW_DAYS = 30L

        val REFERENCE_REGEX = Regex("<([^<>]+)>")

        val SENT_FOLDER_NAMES = listOf(
            "Sent", "Sent Items", "Sent Messages", "Wysłane", "Wyslane",
            "Elementy wysłane", "[Gmail]/Sent Mail", "INBOX.Sent"
        )

        val FILTER_HEADERS = listOf("List-Unsubscribe", "List-Id", "Auto-Submitted", "Precedence")
    }
}
