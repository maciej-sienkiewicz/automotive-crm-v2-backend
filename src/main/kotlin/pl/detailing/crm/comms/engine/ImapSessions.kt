package pl.detailing.crm.comms.engine

import jakarta.mail.Folder
import jakarta.mail.Session
import jakarta.mail.Store
import com.sun.mail.imap.IMAPFolder
import org.springframework.stereotype.Service
import pl.detailing.crm.mailbox.infrastructure.MailAccountEntity
import pl.detailing.crm.mailbox.infrastructure.MailboxEncryptionService
import pl.detailing.crm.shared.ValidationException
import java.text.Normalizer
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
     * Jeden przegląd skrzynki: wybrany folder Wysłanych plus pełna lista folderów.
     * Lista służy diagnostyce i ręcznemu wskazaniu folderu, gdy automat nie trafi.
     */
    data class FolderInspection(val sentFolderName: String?, val allFolderNames: List<String>)

    /**
     * Rozpoznanie folderu Wysłanych — GENERYCZNE, bo u każdego dostawcy nazywa się
     * inaczej („Sent", „Wysłane", „Elementy wysłane", „[Gmail]/Sent Mail", „INBOX.Sent"…).
     *
     * Kolejność sygnałów, od najmocniejszego:
     *  1. atrybut SPECIAL-USE `\Sent` (RFC 6154) — niezależny od języka i nazwy; to on
     *     ma trafiać w 99% skrzynek, bo wystawiają go dziś Gmail, M365 i większość IMAP-ów;
     *  2. heurystyka nazw — po LIŚCIU nazwy, bez diakrytyki i wielkości liter, w wielu
     *     językach; z wykluczeniem folderów o innej roli (Kopie robocze, Kosz, Spam…).
     * Reszta ogona idzie na ręczne wskazanie (override zapamiętany przy koncie).
     */
    fun inspectFolders(store: Store): FolderInspection {
        val folders = runCatching {
            store.defaultFolder.list("*").filterIsInstance<IMAPFolder>()
        }.getOrDefault(emptyList())

        val candidates = folders.map { folder ->
            val attrs = runCatching { folder.attributes.toList() }.getOrDefault(emptyList())
            val separator = runCatching { folder.separator }.getOrDefault('/')
            SentCandidate(
                fullName = folder.fullName,
                leaf = runCatching { folder.name }.getOrDefault(folder.fullName),
                depth = folder.fullName.count { it == separator },
                hasSentAttribute = attrs.any { it.equals(SENT_ATTRIBUTE, ignoreCase = true) },
                hasOtherRoleAttribute = attrs.any { a -> ROLE_ATTRIBUTES.any { it.equals(a, ignoreCase = true) } }
            )
        }
        return FolderInspection(
            sentFolderName = chooseSentFolder(candidates),
            allFolderNames = folders.map { it.fullName }
        )
    }

    /** Cienki wariant dla wołających, którym wystarczy sama nazwa. */
    fun findSentFolderName(store: Store): String? = inspectFolders(store).sentFolderName

    companion object {
        const val DEFAULT_IMAPS_PORT = 993

        /** IDLE re-arms every ~10 min; the socket timeout must comfortably outlive one cycle. */
        const val IDLE_SOCKET_TIMEOUT_MS = "900000"

        private const val SENT_ATTRIBUTE = "\\Sent"

        /** Atrybuty SPECIAL-USE innych ról — folder z którymkolwiek z nich NIE jest Wysłanymi. */
        private val ROLE_ATTRIBUTES = listOf(
            "\\Drafts", "\\Trash", "\\Junk", "\\All", "\\Archive",
            "\\Flagged", "\\Important", "\\Noselect"
        )

        /** Pełne nazwy liścia w wielu językach — najpewniejsze dopasowanie po nazwie. */
        private val KNOWN_SENT_LEAVES = setOf(
            "sent", "sent items", "sent mail", "sent messages",
            "wyslane", "elementy wyslane", "poczta wyslana",
            "gesendet", "gesendete elemente", "gesendete objekte",
            "enviados", "elementos enviados", "correo enviado", "enviada", "mensajes enviados",
            "envoyes", "messages envoyes", "elements envoyes",
            "posta inviata", "inviata",
            "verzonden", "verzonden items",
            "skickat", "skickade meddelanden",
            "odeslane", "odeslana posta",
            "wyslana poczta",
            "отправленные", "已发送", "寄件備份", "送信済み", "보낸편지함"
        )

        /** Rdzenie „wysłane" do dopasowania częściowego, gdy nazwa liścia nie jest czysta. */
        private val SENT_KEYWORDS = listOf(
            "sent", "wyslane", "wyslana", "gesendet", "enviado", "enviada",
            "envoye", "inviata", "verzonden", "skicka", "odeslan",
            "отправлен", "已发送", "送信", "보낸"
        )

        /** Rdzenie innych ról — jeśli liść je zawiera, folderu nie bierzemy za Wysłane. */
        private val OTHER_ROLE_KEYWORDS = listOf(
            "draft", "robocz", "entwurf", "borrador", "brouillon", "bozze", "concept", "kladd", "черновик",
            "trash", "kosz", "papierkorb", "papelera", "corbeille", "cestino", "prullenbak", "deleted", "usuni", "корзина",
            "junk", "spam", "niechcian", "нежелательн",
            "outbox", "skrzynka nadawcza", "postausgang", "bandeja de salida",
            "archiv", "archiw", "all mail", "cala poczta", "wszystkie"
        )

        /** Bez diakrytyki, bez wielkości liter, ł→l — żeby „Wysłane" i „wyslane" były jednym. */
        internal fun normalizeFolderName(name: String): String =
            Normalizer.normalize(name.trim(), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace('\u0142', 'l').replace('\u0141', 'l') // ł, Ł nie rozkłada NFD
                .lowercase()
                .replace(Regex("\\s+"), " ")

        /**
         * Czysty wybór — bez IMAP-a, więc daje się otestować dla dowolnego układu folderów.
         * SPECIAL-USE wygrywa; potem dokładna nazwa liścia; potem częściowe trafienie.
         * Przy remisie wygrywa folder płycej w hierarchii (top-level „Sent" nad „Archiwum/Sent").
         */
        internal fun chooseSentFolder(candidates: List<SentCandidate>): String? {
            candidates.firstOrNull { it.hasSentAttribute }?.let { return it.fullName }

            val usable = candidates
                .filterNot { it.hasOtherRoleAttribute }
                .map { it to normalizeFolderName(it.leaf) }
                .filterNot { (_, leaf) -> OTHER_ROLE_KEYWORDS.any { leaf.contains(it) } }

            usable.filter { (_, leaf) -> leaf in KNOWN_SENT_LEAVES }
                .minByOrNull { (candidate, _) -> candidate.depth }
                ?.let { return it.first.fullName }

            usable.filter { (_, leaf) -> SENT_KEYWORDS.any { leaf.contains(it) } }
                .minByOrNull { (candidate, _) -> candidate.depth }
                ?.let { return it.first.fullName }

            return null
        }
    }
}

/** Kandydat na folder Wysłanych — tyle, ile potrzebuje [ImapSessions.chooseSentFolder]. */
internal data class SentCandidate(
    val fullName: String,
    val leaf: String,
    val depth: Int = 0,
    val hasSentAttribute: Boolean = false,
    val hasOtherRoleAttribute: Boolean = false
)
