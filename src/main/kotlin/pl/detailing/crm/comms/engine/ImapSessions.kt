package pl.detailing.crm.comms.engine

import jakarta.mail.Folder
import jakarta.mail.Session
import jakarta.mail.Store
import com.sun.mail.imap.IMAPFolder
import org.springframework.stereotype.Service
import pl.detailing.crm.mailbox.infrastructure.MailAccountEntity
import pl.detailing.crm.mailbox.infrastructure.MailboxEncryptionService
import pl.detailing.crm.shared.ValidationException
import java.util.Properties

/**
 * Opens IMAP stores for connected accounts. One session per operation, opened and
 * closed per run — cheap Polish hosting providers cap concurrent IMAP sessions per
 * mailbox, so long-lived pooled connections are counterproductive here. The single
 * exception is the IDLE watcher, which owns its own dedicated connection.
 */
@Service
class ImapSessions(
    private val encryptionService: MailboxEncryptionService
) {

    fun openStore(account: MailAccountEntity, forIdle: Boolean = false): Store {
        val host = account.imapHost?.takeIf { it.isNotBlank() }
            ?: throw ValidationException("Skrzynka ${account.emailAddress} nie ma skonfigurowanego serwera IMAP")
        val port = account.imapPort ?: DEFAULT_IMAPS_PORT
        val password = account.encryptedPassword?.let { encryptionService.decrypt(it) }
            ?: throw ValidationException("Skrzynka ${account.emailAddress} nie ma zapisanych poświadczeń")

        val props = Properties().apply {
            put("mail.store.protocol", "imaps")
            put("mail.imaps.host", host)
            put("mail.imaps.port", port.toString())
            put("mail.imaps.ssl.enable", "true")
            put("mail.imaps.connectiontimeout", "10000")
            put("mail.imaps.partialfetch", "false")
            // IDLE must block far longer than a normal fetch; sync operations stay tight.
            put("mail.imaps.timeout", if (forIdle) IDLE_SOCKET_TIMEOUT_MS else "30000")
        }
        val session = Session.getInstance(props)
        val store = session.getStore("imaps")
        store.connect(host, port, account.emailAddress, password)
        return store
    }

    fun openInbox(store: Store, readOnly: Boolean = true): IMAPFolder {
        val folder = store.getFolder("INBOX") as IMAPFolder
        folder.open(if (readOnly) Folder.READ_ONLY else Folder.READ_WRITE)
        return folder
    }

    /**
     * Resolves the server's Sent folder: SPECIAL-USE attribute first, then the usual
     * suspects by name. Null when the server exposes nothing recognisable.
     */
    fun findSentFolderName(store: Store): String? {
        val bySpecialUse = runCatching {
            store.defaultFolder.list("*")
                .filterIsInstance<IMAPFolder>()
                .firstOrNull { folder ->
                    runCatching { folder.attributes.any { it.equals("\\Sent", ignoreCase = true) } }
                        .getOrDefault(false)
                }
                ?.fullName
        }.getOrNull()
        if (bySpecialUse != null) return bySpecialUse

        return SENT_FOLDER_NAMES.firstOrNull { name ->
            runCatching { store.getFolder(name)?.exists() == true }.getOrDefault(false)
        }
    }

    companion object {
        const val DEFAULT_IMAPS_PORT = 993

        /** IDLE re-arms every ~10 min; the socket timeout must comfortably outlive one cycle. */
        const val IDLE_SOCKET_TIMEOUT_MS = "900000"

        private val SENT_FOLDER_NAMES = listOf(
            "Sent", "Sent Items", "Sent Messages", "Wysłane", "Wyslane",
            "Elementy wysłane", "INBOX.Sent", "[Gmail]/Sent Mail"
        )
    }
}
