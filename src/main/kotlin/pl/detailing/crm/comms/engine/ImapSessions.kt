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
    data class FolderInspection(
        val sentFolderName: String?,
        val allFolderNames: List<String>,
        /**
         * Liczba wiadomości w folderach branych pod uwagę jako Wysłane. Pusta, gdy
         * kandydat był jeden (nie ma czego rozstrzygać) albo serwer nie odpowiedział.
         */
        val sentCandidateCounts: Map<String, Int> = emptyMap()
    ) {
        /** „Wiemy na pewno, że pusty" — różne od „nie pytaliśmy". */
        fun isKnownEmpty(folderName: String?): Boolean =
            folderName != null && sentCandidateCounts[folderName] == 0

        fun hasMessages(folderName: String?): Boolean =
            folderName != null && (sentCandidateCounts[folderName] ?: 0) > 0
    }

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

        val byName = folders.associateBy { it.fullName }
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

        /*
         * Sonda: ile wiadomości ma każdy kandydat na Wysłane.
         *
         * Pytamy WYŁĄCZNIE wtedy, gdy kandydatów jest więcej niż jeden — przy jednym
         * nie ma czego rozstrzygać, a każde pytanie to jedna komenda STATUS do serwera.
         * Skrzynka z jednym folderem Wysłanych (czyli większość) nie płaci za to nic.
         *
         * Każde zapytanie osobno w runCatching: serwer, który nie odpowie na STATUS,
         * ma zostawić kandydata z „nie wiem", a nie wywrócić całego rozpoznania.
         */
        val plausible = plausibleSentCandidates(candidates)
        val counts = if (plausible.size < 2) emptyMap() else plausible.mapNotNull { candidate ->
            val folder = byName[candidate.fullName] ?: return@mapNotNull null
            runCatching { folder.messageCount }
                .getOrNull()
                ?.takeIf { it >= 0 }
                ?.let { candidate.fullName to it }
        }.toMap()

        val probed = candidates.map { it.copy(messageCount = counts[it.fullName]) }
        return FolderInspection(
            sentFolderName = chooseSentFolder(probed),
            allFolderNames = folders.map { it.fullName },
            sentCandidateCounts = counts
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
         * Kandydaci, których nazwa albo atrybut w ogóle dopuszczają do roli Wysłanych.
         * Wydzielone, bo tę samą listę bierze sonda liczby wiadomości i sam wybór.
         */
        internal fun plausibleSentCandidates(candidates: List<SentCandidate>): List<SentCandidate> =
            candidates
                .filterNot { it.hasOtherRoleAttribute }
                .map { it to normalizeFolderName(it.leaf) }
                .filterNot { (_, leaf) -> OTHER_ROLE_KEYWORDS.any { leaf.contains(it) } }
                .filter { (candidate, leaf) ->
                    candidate.hasSentAttribute ||
                        leaf in KNOWN_SENT_LEAVES ||
                        SENT_KEYWORDS.any { leaf.contains(it) }
                }
                .map { it.first }

        /** Jak bardzo nazwa/atrybut wskazują na Wysłane: 3 = SPECIAL-USE, 2 = dokładna nazwa, 1 = rdzeń. */
        private fun nameConfidence(candidate: SentCandidate): Int {
            if (candidate.hasSentAttribute) return 3
            val leaf = normalizeFolderName(candidate.leaf)
            if (leaf in KNOWN_SENT_LEAVES) return 2
            if (SENT_KEYWORDS.any { leaf.contains(it) }) return 1
            return 0
        }

        /**
         * Czysty wybór — bez IMAP-a, więc daje się otestować dla dowolnego układu folderów.
         *
         * ── Pierwsze kryterium: ZAWARTOŚĆ, nie nazwa ────────────────────────────
         *
         * Skrzynki po latach pracy mają po kilka folderów wysłanych naraz: polski
         * „Elementy wysłane" obok angielskiego „Sent", a prawdziwy ruch pod
         * „INBOX.Sent". Wcześniej rozstrzygała nazwa i głębokość, więc przy remisie
         * wygrywał ten, którego serwer wymienił pierwszy — i potrafił to być folder
         * pusty, założony kiedyś przez przypadkowego klienta pocztowego. Skutek:
         * odpowiedzi wysyłane spoza CRM-a po cichu nie dopinały się do rozmów.
         *
         * Nazwa bywa myląca u każdego dostawcy inaczej; pusty folder jest pusty
         * wszędzie tak samo. Dlatego folder, o którym WIEMY, że coś w nim leży, bije
         * folder, o którym wiemy, że jest pusty — nawet jeśli ten drugi ma ładniejszą
         * nazwę albo atrybut SPECIAL-USE.
         *
         * „Nie wiem" (sonda pominięta albo serwer nie odpowiedział) nie dyskwalifikuje
         * nikogo: wtedy decyduje dokładnie ta sama kolejność co wcześniej — SPECIAL-USE,
         * dokładna nazwa liścia, częściowe trafienie, a przy remisie płycej w hierarchii.
         */
        internal fun chooseSentFolder(candidates: List<SentCandidate>): String? {
            val plausible = plausibleSentCandidates(candidates)
            if (plausible.isEmpty()) return null

            val withMail = plausible.filter { (it.messageCount ?: 0) > 0 }
            if (withMail.isNotEmpty()) {
                return withMail.minWithOrNull(
                    compareByDescending<SentCandidate> { nameConfidence(it) }.thenBy { it.depth }
                )!!.fullName
            }

            /*
             * Nikt nie ma wiadomości albo nikogo nie odpytaliśmy. Kandydatów, o których
             * WIEMY, że są puste, odkładamy na koniec — ale ich nie wyrzucamy: świeża
             * skrzynka, w której nikt jeszcze nic nie wysłał, ma wyłącznie takich.
             */
            val knownEmptyLast = plausible.sortedWith(
                compareBy<SentCandidate> { it.messageCount == 0 }
                    .thenByDescending { nameConfidence(it) }
                    .thenBy { it.depth }
            )
            return knownEmptyLast.firstOrNull()?.fullName
        }
    }
}

/** Kandydat na folder Wysłanych — tyle, ile potrzebuje [ImapSessions.chooseSentFolder]. */
internal data class SentCandidate(
    val fullName: String,
    val leaf: String,
    val depth: Int = 0,
    val hasSentAttribute: Boolean = false,
    val hasOtherRoleAttribute: Boolean = false,
    /**
     * Ile wiadomości serwer zgłasza w tym folderze; null = nie pytaliśmy albo
     * serwer nie odpowiedział.
     *
     * `null` i `0` to DWIE RÓŻNE rzeczy i nie wolno ich mylić: „nie wiem" nie może
     * dyskwalifikować kandydata, a „wiem, że pusty" musi.
     */
    val messageCount: Int? = null
)
