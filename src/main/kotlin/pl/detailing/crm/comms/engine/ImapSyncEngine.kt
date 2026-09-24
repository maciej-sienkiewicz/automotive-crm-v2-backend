package pl.detailing.crm.comms.engine

import com.sun.mail.imap.IMAPFolder
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Store
import jakarta.mail.UIDFolder
import jakarta.mail.internet.MimeMessage
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import pl.detailing.crm.comms.domain.CommFolderKind
import pl.detailing.crm.comms.domain.CommOutboxType
import pl.detailing.crm.comms.infrastructure.CommMessageRepository
import pl.detailing.crm.comms.infrastructure.MimeEmailParser
import pl.detailing.crm.mailbox.domain.MailAccountStatus
import pl.detailing.crm.mailbox.domain.MailProviderType
import pl.detailing.crm.mailbox.infrastructure.MailAccountEntity
import pl.detailing.crm.mailbox.infrastructure.MailAccountRepository
import pl.detailing.crm.mailbox.infrastructure.MailFolderCursorEntity
import pl.detailing.crm.mailbox.infrastructure.MailFolderCursorRepository
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
    private val folderCursorRepository: MailFolderCursorRepository,
    private val messageRepository: CommMessageRepository,
    private val imapSessions: ImapSessions,
    private val parser: MimeEmailParser,
    private val ingestService: CommsIngestService,
    private val readService: CommsReadService,
    private val outboxRepository: pl.detailing.crm.comms.infrastructure.CommOutboxRepository,
    private val progressRegistry: SyncProgressRegistry,
    private val followUps: ObjectProvider<AccountSyncFollowUp>
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
            // Pod tą samą blokadą co import: porządki w wątkach skrzynki nie mogą się
            // przeplatać z wpinaniem do nich nowej poczty.
            followUps.orderedStream().forEach { followUp ->
                runCatching { followUp.afterSync(account) }.onFailure {
                    log.error("Porządki po synchronizacji skrzynki {} nieudane: {}", account.emailAddress, it.message, it)
                }
            }
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

            /*
             * Foldery Wysłanych — WSZYSTKIE, nie jeden.
             *
             * Wcześniej stał tu wybór: ręczne nadpisanie, a jak nie ma, to rozpoznanie
             * generyczne. Wybór przegrał na produkcji dwa razy pod rząd, za każdym razem
             * inaczej. Skrzynka biuro@carslab.pl ma trzy foldery wysłanych naraz:
             * „Elementy wysłane" (pusty), „Sent" (106 wiadomości) i „INBOX.Sent" (67) —
             * zakładał je każdy kolejny program pocztowy. Najpierw wygrywał pusty, bo
             * rozstrzygała nazwa; po poprawce na zawartość wygrał „Sent", który stoi na
             * UID 1093 od trzech dni, podczas gdy klient pisze z innego programu do innego
             * folderu.
             *
             * Przegrany zakład nie daje ŻADNEGO objawu: log mówi „0 nowych", bo w tym
             * folderze faktycznie nic nowego nie ma. Rozjazd wychodzi dopiero wtedy, gdy
             * ktoś zauważy brak własnej odpowiedzi w rozmowie — czyli za późno.
             *
             * Dlatego skanujemy każdy wiarygodny folder. Kosztuje to jedno otwarcie
             * folderu na przebieg (skrzynki z jednym folderem Wysłanych, czyli większość,
             * nie płacą nic), a duplikaty odsiewa `ingest` po Message-ID — ta sama
             * wiadomość leżąca w dwóch folderach wejdzie raz.
             */
            val inspection = imapSessions.inspectFolders(store)

            /*
             * Ręczne nadpisanie nie zawęża już skanowania do jednego folderu — byłoby to
             * dokładnie to, co przestaliśmy robić. Zamiast tego DOPISUJE folder do listy
             * i stawia go na jej czele: człowiek, który świadomie wskazał folder, wie
             * o swojej skrzynce coś, czego heurystyka nie wie, ale nie musi wiedzieć
             * o pozostałych.
             */
            val override = account.sentFolderName
                ?.takeIf { it.isNotBlank() }
                ?.takeIf { it in inspection.allFolderNames }
            val sentFolders = (listOfNotNull(override) + inspection.sentFolderNames).distinct()

            // Przy koncie zostaje czoło listy — to folder, do którego CRM robi APPEND
            // po własnej wysyłce, a tam wybrać trzeba, bo APPEND jest jeden.
            val primary = sentFolders.firstOrNull()
            if (primary != null && primary != account.sentFolderName) {
                log.info("[COMMS] {}: folder Wysłanych rozpoznany jako '{}'", account.emailAddress, primary)
                account.sentFolderName = primary
            }

            if (sentFolders.isEmpty()) {
                // Nadpisanie wskazywało na folder, którego już nie ma — czyścimy, żeby
                // przy następnym przebiegu zadziałało rozpoznanie automatyczne.
                if (account.sentFolderName != null) account.sentFolderName = null
                log.warn(
                    "[COMMS] {}: nie rozpoznano żadnego folderu Wysłanych — odpowiedzi wysłane spoza " +
                        "CRM-a nie zostaną dopięte do rozmów. Foldery skrzynki: {}",
                    account.emailAddress, inspection.allFolderNames
                )
            } else {
                if (sentFolders.size > 1) {
                    log.debug(
                        "[COMMS] {}: foldery Wysłanych do przeskanowania: {}",
                        account.emailAddress, sentFolders
                    )
                }
                val cursors = folderCursorRepository.findByAccountId(account.id).associateBy { it.folderName }
                sentFolders.forEach { folderName ->
                    val cursor = cursors[folderName]
                    syncFolder(
                        store, account, folderName, CommFolderKind.SENT,
                        cursor?.uidValidity, cursor?.lastUid,
                        // Lista folderów tylko dla Wysłanych: to jedyne foldery, które
                        // WYBIERAMY, więc jako jedyne możemy wybrać źle. INBOX nazywa się
                        // INBOX i nie ma tu czego diagnozować.
                        inspection.allFolderNames
                    )?.let { (validity, lastUid) ->
                        saveCursor(cursor, account.id, folderName, validity, lastUid)
                    }
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
            // Pasek postępu znika niezależnie od wyniku — wiszący pasek po błędzie
            // wyglądałby jak wieczna synchronizacja.
            progressRegistry.finish(account.id)
        }
    }

    /**
     * Zapis znacznika folderu. Wiersz zakładamy dopiero przy pierwszym udanym skanie —
     * folder, którego nie dało się otworzyć, nie zostawia po sobie śladu, więc lista
     * kursorów nie puchnie o foldery, których w skrzynce już nie ma.
     */
    private fun saveCursor(
        existing: MailFolderCursorEntity?,
        accountId: UUID,
        folderName: String,
        uidValidity: Long,
        lastUid: Long
    ) {
        val cursor = existing ?: MailFolderCursorEntity(accountId = accountId, folderName = folderName)
        cursor.uidValidity = uidValidity
        cursor.lastUid = lastUid
        cursor.updatedAt = Instant.now()
        folderCursorRepository.save(cursor)
    }

    /** Returns the new (uidValidity, lastUid) pair, or null when the folder could not be read. */
    private fun syncFolder(
        store: Store,
        account: MailAccountEntity,
        folderName: String,
        folderKind: CommFolderKind,
        savedUidValidity: Long?,
        savedLastUid: Long?,
        /**
         * Lista folderów skrzynki — wypisywana tylko wtedy, gdy folder nie oddaje nic
         * mimo poprawnego otwarcia. Bez niej „0 nowych" jest nieodróżnialne od
         * „czytamy nie ten folder, co trzeba", a jedno od drugiego dzieli akurat ta lista.
         */
        allFolderNames: List<String> = emptyList()
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

            /*
             * Zmiana UIDVALIDITY to komunikat serwera „folder, który znałeś, przestał
             * istnieć — ten jest inny" (RFC 3501). Unieważnia WSZYSTKIE zapamiętane
             * UID-y tego folderu, więc zaczynamy od zera i czytamy go od nowa.
             *
             * Musi być widoczne w logu na poziomie INFO: bez tej linii rozjazd między
             * numeracją zapisaną przy wiadomościach a numeracją konta wychodzi dopiero
             * przy ręcznym zapytaniu do bazy — a to jest moment, w którym folder może
             * po cichu przestać cokolwiek oddawać.
             */
            if (savedUidValidity != null && savedUidValidity != uidValidity) {
                log.info(
                    "[COMMS] {}: folder {} zmienił UIDVALIDITY {} → {} — pełny skan od nowa, " +
                        "wcześniejsze UID-y przestały obowiązywać",
                    account.emailAddress, folderName, savedUidValidity, uidValidity
                )
            }
            val startUid = if (fullResync) 1L else (savedLastUid ?: 0L) + 1
            val isBackfill = startUid <= 1L
            val cutoff = Instant.now().minus(BACKFILL_WINDOW_DAYS, ChronoUnit.DAYS)

            val messages = uidFolder.getMessagesByUID(startUid, UIDFolder.LASTUID) ?: emptyArray()
            val watermark = UidWatermark(savedLastUid?.takeIf { !fullResync } ?: 0L)
            var ingested = 0

            // Pierwszy import zgłasza pasek postępu: interfejs pokazuje wtedy stan
            // „trwa synchronizacja" zamiast lawiny powiadomień o nowej poczcie.
            if (isBackfill) progressRegistry.addPlanned(account.id, messages.size)

            for (message in messages) {
                if (isBackfill) progressRegistry.tick(account.id)
                val uid = runCatching { uidFolder.getUID(message) }.getOrDefault(-1L)
                if (uid <= 0) continue
                // getMessagesByUID(start, LASTUID) always returns the last message, even below start.
                if (uid < startUid) continue

                try {
                    val mime = message as? MimeMessage
                    if (mime == null) {
                        // Nie-MIME z serwera nie stanie się MIME-em w kolejnym przebiegu —
                        // to pominięcie trwałe, więc znacznik może iść dalej.
                        watermark.done(uid)
                        continue
                    }
                    val sentAt = (mime.sentDate ?: mime.receivedDate)?.toInstant()
                    if (isBackfill && sentAt != null && sentAt.isBefore(cutoff)) {
                        watermark.done(uid)
                        continue
                    }
                    val parsed = parser.parse(mime, uid)
                    if (ingestService.ingest(account, folderKind, parsed, uidValidity, backfill = isBackfill)) ingested++
                    watermark.done(uid)
                } catch (ex: Exception) {
                    // A single unparseable message must not block the rest of the folder —
                    // but it must not be written off either. Zatrzymujemy na niej znacznik
                    // UID, więc kolejny przebieg spróbuje ponownie; wcześniej znacznik
                    // przeskakiwał także nieudane wiadomości, a to znaczy „przepadła na
                    // zawsze": po UID-zie już nie wróci, a w skrzynce CRM-a jej nie ma.
                    // Powtórka jest bezpieczna — ingest odsiewa duplikaty po Message-ID.
                    watermark.failed(uid)
                    log.warn(
                        "Nie udało się zaimportować wiadomości uid={} w folderze {} skrzynki {} — ponowię: {}",
                        uid, folderName, account.emailAddress, ex.message
                    )
                }
            }

            if (ingested > 0) {
                log.info(
                    "[COMMS] {}: +{} wiadomości z {} ({})",
                    account.emailAddress, ingested, folderName, if (fullResync) "pełny skan" else "delta"
                )
            } else {
                /*
                 * Bez tej linii „przeskanowano, nic nowego" jest nieodróżnialne od „w ogóle
                 * nie skanowano" — a to właśnie ta różnica decyduje, gdzie zginęła odpowiedź.
                 *
                 * `zwrócono` rozdziela dwie sytuacje, które do tej pory wyglądały tak samo:
                 * folder oddał komplet znanych wiadomości (nic nowego, wszystko w porządku)
                 * i folder nie oddał NICZEGO (czytamy pustkę albo nie ten folder).
                 */
                log.debug(
                    "[COMMS] {}: przeskanowano {} ({}), 0 nowych (startUid={}, zwrócono={})",
                    account.emailAddress, folderName, if (fullResync) "pełny skan" else "delta",
                    startUid, messages.size
                )
            }

            /*
             * Folder otwarty poprawnie, a mimo to nigdy nie oddał ani jednej wiadomości.
             * Sam w sobie bywa to prawdą (świeża skrzynka, nikt nic nie wysłał), ale jest
             * też jedynym objawem sytuacji, w której czytamy NIE TEN folder — a wtedy
             * odpowiedzi wysyłane spoza CRM-a po cichu nie dopinają się do rozmów.
             *
             * Lista folderów skrzynki jest tu jedyną informacją, która pozwala to rozstrzygnąć
             * bez dostępu do cudzej poczty, więc idzie do logu razem z ostrzeżeniem.
             */
            if (messages.isEmpty() && watermark.value() == 0L && allFolderNames.isNotEmpty()) {
                log.warn(
                    "[COMMS] {}: folder {} ({}) jest rozpoznany i otwiera się, ale nie oddał ani " +
                        "jednej wiadomości. Jeśli w skrzynce coś w nim leży, czytamy nie ten folder. " +
                        "Foldery skrzynki: {}",
                    account.emailAddress, folderName, folderKind, allFolderNames
                )
            }
            return uidValidity to watermark.value()
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
        // Wiadomości z zaległym MARK_UNSEEN dopiero co oznaczono w CRM jako nieprzeczytane;
        // serwer może jeszcze mieć na nich \Seen (komenda czyszcząca nie wykonała się),
        // więc pominięcie ich tutaj chroni przed cofnięciem oznaczenia w wyścigu.
        val skipUnseen = outboxRepository.findPendingMessageIds(account.id, CommOutboxType.MARK_UNSEEN).toSet()

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
                if (entity.id in skipUnseen) continue
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

/**
 * Dokąd w folderze doszliśmy „na pewno".
 *
 * Znacznik UID to obietnica: wszystko do tej liczby włącznie jest już w CRM-ie i nie
 * będzie pobierane ponownie. Wiadomość, której nie udało się zaimportować, tej
 * obietnicy nie spełnia — a skoro delta pobiera tylko UID-y WYŻSZE od znacznika,
 * przesunięcie go ponad nią kasuje ją z CRM-a na zawsze, mimo że na serwerze leży
 * dalej. Dlatego znacznik zatrzymuje się TUŻ PRZED pierwszą nieudaną wiadomością,
 * a pozostałe z tego przebiegu i tak lecą do importu: awaria jednej wiadomości
 * opóźnia potwierdzenie, nie blokuje folderu.
 *
 * Ponowny import tej samej wiadomości jest bezpieczny — ingest deduplikuje po
 * Message-ID — więc jedynym kosztem powtórki jest jedno zapytanie do bazy.
 *
 * Kompromis jest świadomy: wiadomość, której nie da się zaimportować NIGDY, każe
 * kolejnym przebiegom raz po raz przeglądać ogon folderu. Wolimy ten koszt (widoczny
 * w logu jako powtarzające się ostrzeżenie z tym samym UID-em, więc dający się
 * zdiagnozować) niż cichą utratę korespondencji klienta, której nikt nie zauważy.
 */
internal class UidWatermark(startingFrom: Long) {

    private var highestDone = startingFrom
    private var lowestFailed: Long? = null

    /** Wiadomość zamknięta: zapisana, zduplikowana albo świadomie pominięta. */
    fun done(uid: Long) {
        if (uid > highestDone) highestDone = uid
    }

    /** Wiadomość do ponowienia w kolejnym przebiegu. */
    fun failed(uid: Long) {
        val current = lowestFailed
        if (current == null || uid < current) lowestFailed = uid
    }

    fun value(): Long = lowestFailed?.let { minOf(highestDone, it - 1) } ?: highestDone
}
