package pl.detailing.crm.comms.engine

import com.sun.mail.imap.IMAPFolder
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Store
import jakarta.mail.UIDFolder
import jakarta.mail.internet.MimeMessage
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import pl.detailing.crm.comms.domain.CommFolderKind
import pl.detailing.crm.comms.infrastructure.CommMessageRepository
import pl.detailing.crm.comms.infrastructure.MimeEmailParser
import pl.detailing.crm.mailbox.domain.MailAccountStatus
import pl.detailing.crm.mailbox.domain.MailProviderType
import pl.detailing.crm.mailbox.infrastructure.MailAccountEntity
import pl.detailing.crm.mailbox.infrastructure.MailAccountRepository
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The ingest half of the sync architecture: one-way INBOX + Sent delta sync per
 * account, plus \Seen reconciliation for messages still unread in the CRM.
 *
 * Delta strategy: UIDs above the last stored one are new. A UIDVALIDITY change means
 * the server renumbered everything — treated as a normal event, not a failure: we
 * re-scan the window and rely on Message-ID dedupe in [CommsIngestService].
 */
@Service
class ImapSyncEngine(
    private val accountRepository: MailAccountRepository,
    private val messageRepository: CommMessageRepository,
    private val imapSessions: ImapSessions,
    private val parser: MimeEmailParser,
    private val ingestService: CommsIngestService,
    private val readService: CommsReadService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Per-account guard: IDLE pokes and the scheduler must never sync the same box concurrently. */
    private val syncLocks = ConcurrentHashMap<UUID, AtomicBoolean>()

    /**
     * Safety-net full pass. IDLE delivers near-real-time nudges for INBOX; this loop
     * catches everything IDLE can miss (Sent folder, dropped IDLE sessions, flags).
     */
    @Scheduled(fixedDelayString = "\${comms.sync.interval-ms:180000}", initialDelay = 30_000)
    fun syncAllAccounts() {
        val accounts = accountRepository.findByStatusAndProviderType(
            MailAccountStatus.ACTIVE, MailProviderType.IMAP_SMTP
        )
        accounts.forEach { account ->
            // One broken mailbox must never stop the others.
            try {
                syncAccount(account.id)
            } catch (ex: Exception) {
                log.error("Synchronizacja skrzynki {} nieudana: {}", account.emailAddress, ex.message)
            }
        }
    }

    /** Entry point for the scheduler, IDLE nudges and the manual "sync now" endpoint. */
    fun syncAccount(accountId: UUID) {
        val lock = syncLocks.computeIfAbsent(accountId) { AtomicBoolean(false) }
        if (!lock.compareAndSet(false, true)) return
        try {
            val account = accountRepository.findById(accountId).orElse(null) ?: return
            if (account.status == MailAccountStatus.DISABLED) return
            doSync(account)
        } finally {
            lock.set(false)
        }
    }

    private fun doSync(account: MailAccountEntity) {
        var store: Store? = null
        try {
            store = imapSessions.openStore(account)

            syncFolder(
                store, account, "INBOX", CommFolderKind.INBOX,
                account.inboxUidValidity, account.inboxLastUid
            )?.let { (validity, lastUid) ->
                account.inboxUidValidity = validity
                account.inboxLastUid = lastUid
            }

            imapSessions.findSentFolderName(store)?.let { sentName ->
                syncFolder(
                    store, account, sentName, CommFolderKind.SENT,
                    account.sentUidValidity, account.sentLastUid
                )?.let { (validity, lastUid) ->
                    account.sentUidValidity = validity
                    account.sentLastUid = lastUid
                }
            }

            reconcileSeenFlags(store, account)

            account.status = MailAccountStatus.ACTIVE
            account.lastError = null
            account.lastSyncAt = Instant.now()
        } catch (ex: AuthenticationFailedException) {
            // Expected failure mode after a password change — surface in the UI, don't spam logs.
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

    /** Returns the new (uidValidity, lastUid) pair, or null when the folder could not be read. */
    private fun syncFolder(
        store: Store,
        account: MailAccountEntity,
        folderName: String,
        folderKind: CommFolderKind,
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
            val fullResync = savedUidValidity == null || savedUidValidity != uidValidity
            val startUid = if (fullResync) 1L else (savedLastUid ?: 0L) + 1
            val isBackfill = startUid <= 1L
            val cutoff = Instant.now().minus(BACKFILL_WINDOW_DAYS, ChronoUnit.DAYS)

            val messages = uidFolder.getMessagesByUID(startUid, UIDFolder.LASTUID) ?: emptyArray()
            var maxUid = savedLastUid?.takeIf { !fullResync } ?: 0L
            var ingested = 0

            for (message in messages) {
                val uid = runCatching { uidFolder.getUID(message) }.getOrDefault(-1L)
                if (uid <= 0) continue
                // getMessagesByUID(start, LASTUID) always returns the last message, even below start.
                if (uid < startUid) continue

                try {
                    val mime = message as? MimeMessage ?: continue
                    val sentAt = (mime.sentDate ?: mime.receivedDate)?.toInstant()
                    if (isBackfill && sentAt != null && sentAt.isBefore(cutoff)) {
                        if (uid > maxUid) maxUid = uid
                        continue
                    }
                    val parsed = parser.parse(mime, uid)
                    if (ingestService.ingest(account, folderKind, parsed, uidValidity)) ingested++
                } catch (ex: Exception) {
                    // A single unparseable message must not block the rest of the folder.
                    log.warn(
                        "Pominięto wiadomość uid={} w folderze {} skrzynki {}: {}",
                        uid, folderName, account.emailAddress, ex.message
                    )
                }
                if (uid > maxUid) maxUid = uid
            }

            if (ingested > 0) {
                log.info(
                    "[COMMS] {}: +{} wiadomości z {} ({})",
                    account.emailAddress, ingested, folderName, if (fullResync) "pełny skan" else "delta"
                )
            }
            return uidValidity to maxUid
        } finally {
            runCatching { folder.close(false) }
        }
    }

    /**
     * Server → CRM direction of read-state sync. Only messages the CRM still shows as
     * unread need checking — the set only shrinks, so this stays a few bytes per run.
     * A flag set on the server means someone read the mail in an external client.
     */
    private fun reconcileSeenFlags(store: Store, account: MailAccountEntity) {
        val uidValidity = account.inboxUidValidity ?: return
        val unread = messageRepository.findUnreadWithUid(account.id, CommFolderKind.INBOX, uidValidity)
        if (unread.isEmpty()) return

        val folder = runCatching { store.getFolder("INBOX") as IMAPFolder }.getOrNull() ?: return
        runCatching { folder.open(Folder.READ_ONLY) }.getOrElse { return }
        try {
            if (folder.uidValidity != uidValidity) return
            val byUid = unread.associateBy { it.imapUid!! }
            val imapMessages = runCatching {
                folder.getMessagesByUID(byUid.keys.toLongArray())
            }.getOrNull() ?: return

            for (imapMessage in imapMessages) {
                if (imapMessage == null) continue
                val uid = runCatching { folder.getUID(imapMessage) }.getOrDefault(-1L)
                val entity = byUid[uid] ?: continue
                val seen = runCatching { imapMessage.isSet(Flags.Flag.SEEN) }.getOrDefault(false)
                if (!seen || entity.isRead) continue
                readService.markReadFromServer(entity)
            }
        } finally {
            runCatching { folder.close(false) }
        }
    }

    companion object {
        /** Initial import window; older history is intentionally left on the server. */
        const val BACKFILL_WINDOW_DAYS = 90L
    }
}
