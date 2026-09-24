package pl.detailing.crm.comms.engine

import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.comms.domain.AutomatedMailDetector
import pl.detailing.crm.comms.domain.CommDirection
import pl.detailing.crm.comms.domain.CommOutboundSentEvent
import pl.detailing.crm.comms.domain.CommFolderKind
import pl.detailing.crm.comms.domain.CommReadSource
import pl.detailing.crm.comms.domain.CommSendStatus
import pl.detailing.crm.comms.domain.CommInboundMessageStoredEvent
import pl.detailing.crm.comms.domain.CommThreadChangedEvent
import pl.detailing.crm.comms.domain.CommThreadKind
import pl.detailing.crm.comms.domain.EmailTextCleaner
import pl.detailing.crm.comms.domain.InboundRoute
import pl.detailing.crm.comms.domain.InboundRouter
import pl.detailing.crm.comms.domain.MailAddressBook
import pl.detailing.crm.comms.domain.MailAddressDirectory
import pl.detailing.crm.comms.domain.ParsedEmail
import pl.detailing.crm.comms.infrastructure.CommAttachmentEntity
import pl.detailing.crm.comms.infrastructure.CommAttachmentRepository
import pl.detailing.crm.comms.infrastructure.CommMessageEntity
import pl.detailing.crm.comms.infrastructure.CommMessageRepository
import pl.detailing.crm.comms.infrastructure.CommThreadEntity
import pl.detailing.crm.comms.infrastructure.CommThreadRepository
import pl.detailing.crm.comms.infrastructure.EmailHtmlSanitizer
import pl.detailing.crm.mailbox.infrastructure.MailAccountEntity
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Persists one parsed e-mail: dedupe, threading, sanitising, aggregates, live event.
 * Everything CRM-side hangs off the internal message/thread ids created here — the
 * volatile IMAP identity (uid + uidvalidity) is carried along but never referenced
 * by leads or the UI.
 */
@Service
class CommsIngestService(
    private val threadRepository: CommThreadRepository,
    private val messageRepository: CommMessageRepository,
    private val attachmentRepository: CommAttachmentRepository,
    private val htmlSanitizer: EmailHtmlSanitizer,
    private val textCleaner: EmailTextCleaner,
    private val eventPublisher: ApplicationEventPublisher,
    private val addressDirectory: MailAddressDirectory
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @return true when a new message was stored, false on dedupe.
     * REQUIRES_NEW: each message commits on its own, so one poison message
     * cannot roll back a whole sync batch.
     *
     * [backfill] = pierwszy import skrzynki. Wiadomości sprzed lat nie są „nowe" —
     * zdarzenie zmiany wątku idzie bez flagi newMessage, żeby interfejs odświeżał
     * listę, ale nie strzelał powiadomieniem przy każdej z setek historycznych.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun ingest(
        account: MailAccountEntity,
        folderKind: CommFolderKind,
        parsed: ParsedEmail,
        uidValidity: Long?,
        backfill: Boolean = false
    ): Boolean {
        val messageIdHdr = parsed.messageId
            ?: syntheticMessageId(account.id, parsed)

        val existing = messageRepository.findByAccountIdAndMessageIdHdr(account.id, messageIdHdr)
        if (existing != null) {
            // Same message seen again — e.g. the copy of our SMTP send coming back from
            // the Sent folder, or a re-fetch after UIDVALIDITY reset. Adopt the fresher
            // IMAP identity, never duplicate.
            if (parsed.imapUid != null) {
                existing.imapUid = parsed.imapUid
                existing.imapUidValidity = uidValidity
                if (existing.sendStatus == CommSendStatus.SENDING) {
                    existing.sendStatus = CommSendStatus.SENT
                }
                messageRepository.save(existing)
            }
            return false
        }

        val direction =
            if (folderKind == CommFolderKind.SENT) CommDirection.OUTBOUND else CommDirection.INBOUND
        val book = addressDirectory.addressBook(account.studioId)
        // Czym jest przychodzący mail, rozstrzygamy PRZED wyborem wątku — zgłoszenie
        // z formularza i zwrot serwera mają inne reguły niż zwykła korespondencja.
        val route = if (direction == CommDirection.INBOUND) InboundRouter.route(parsed, book) else null
        val participantEmail = when (route) {
            // Drugą stroną zgłoszenia jest klient z Reply-To, nie robot formularza.
            is InboundRoute.FormRelay -> route.clientEmail
            else -> participantOf(account, parsed, direction)
        }
        val participantName = when (route) {
            is InboundRoute.FormRelay -> route.clientName
            // Podpis robota („Carslab"), studia albo serwera („Mail Delivery System")
            // nie jest nazwą klienta i nie może zostać nazwą rozmowy.
            is InboundRoute.FormRobot, InboundRoute.DeliveryReport, InboundRoute.OwnMailbox -> null
            InboundRoute.Direct -> parsed.fromName
            null -> null
        }

        val thread = resolveThread(account, parsed, route, book, participantEmail, participantName)
        val messageId = UUID.randomUUID()

        val savedAttachments = parsed.attachments.map { att ->
            attachmentRepository.save(
                CommAttachmentEntity(
                    id = UUID.randomUUID(),
                    studioId = account.studioId,
                    messageId = messageId,
                    fileName = att.fileName,
                    contentType = att.contentType,
                    contentId = att.contentId,
                    isInline = att.inline,
                    sizeBytes = att.content.size.toLong(),
                    content = att.content
                )
            )
        }
        val cidMap = savedAttachments
            .filter { it.contentId != null }
            .associate { it.contentId!! to it.id }

        val bodyHtmlSafe = parsed.bodyHtml
            ?.let { htmlSanitizer.sanitize(it, messageId, cidMap) }
            ?: parsed.bodyText?.let { htmlSanitizer.textAsHtml(it) }
        val bodyTextClean = textCleaner.clean(parsed.bodyHtml, parsed.bodyText)

        // Our own outbound mail is read by definition; inbound honours the server flag
        // (initial backfill must not flood the UI with years of "unread").
        val isRead = direction == CommDirection.OUTBOUND || parsed.seen
        val message = CommMessageEntity(
            id = messageId,
            studioId = account.studioId,
            accountId = account.id,
            threadId = thread.id,
            direction = direction,
            folderKind = folderKind,
            messageIdHdr = messageIdHdr,
            inReplyTo = parsed.inReplyTo,
            referencesIds = parsed.references.takeIf { it.isNotEmpty() }?.joinToString(" "),
            fromEmail = parsed.fromEmail,
            fromName = parsed.fromName,
            toEmails = parsed.toEmails.takeIf { it.isNotEmpty() }?.joinToString(", "),
            ccEmails = parsed.ccEmails.takeIf { it.isNotEmpty() }?.joinToString(", "),
            subject = parsed.subject?.take(1000),
            sentAt = parsed.sentAt,
            bodyHtmlSafe = bodyHtmlSafe,
            bodyText = parsed.bodyText,
            bodyTextClean = bodyTextClean,
            hasAttachments = savedAttachments.any { !it.isInline },
            imapUid = parsed.imapUid,
            imapUidValidity = uidValidity,
            isRead = isRead,
            readSource = if (isRead && direction == CommDirection.INBOUND) CommReadSource.EXTERNAL else null,
            readAt = if (isRead) parsed.sentAt else null,
            sendStatus = if (direction == CommDirection.OUTBOUND) CommSendStatus.SENT else CommSendStatus.RECEIVED,
            replyToEmail = parsed.replyToEmail,
            replyToName = parsed.replyToName
        )
        messageRepository.save(message)

        refreshThreadAggregates(thread, message, participantName, route)

        // „Nowa wiadomość" znaczy: przyszła do nas. Wiadomość WYCHODZĄCA trafia tu
        // dwiema drogami — wysyłka z CRM-a (SendMailHandler zapisuje kopię tą samą
        // ścieżką) i odpowiedź wysłana z telefonu czy Outlooka, zassana potem z
        // folderu Wysłane — i w obu przypadkach jej autor doskonale wie, że ją wysłał.
        // Powiadomienie „Masz nową wiadomość w skrzynce" tuż po kliknięciu „Wyślij"
        // jest po prostu nieprawdziwe. Zdarzenie idzie dalej bez tej flagi: lista
        // wątków ma się odświeżyć, tylko bez zaczepiania użytkownika.
        eventPublisher.publishEvent(
            CommThreadChangedEvent(
                studioId = account.studioId,
                threadId = thread.id,
                // Zwrot serwera pocztowego nie jest wiadomością, dla której warto
                // kogokolwiek zaczepiać — trafia prosto do archiwum.
                newMessage = !backfill && direction == CommDirection.INBOUND &&
                    route != InboundRoute.DeliveryReport
            )
        )
        // Po zatwierdzeniu tej transakcji automat formularzy sprawdzi nadawcę —
        // stąd osobne zdarzenie z adresem w środku, żeby nieistotne wiadomości
        // odpadały bez ponownego czytania wiersza.
        // Odpowiedź wysłana poza CRM-em (Outlook, telefon, webmail) wraca do nas
        // z folderu Wysłane. Bez tego zdarzenia lead zostawał „Nowy" i bez czasu
        // pierwszej reakcji, mimo że rozmowa dawno ruszyła — a na tym stoją
        // statystyki leadów. Kopia naszej własnej wysyłki z CRM-a tu nie dociera:
        // odpada wyżej na dedupie po Message-ID.
        //
        // Automaty (autorespondery, newslettery) nie są reakcją człowieka i nie
        // mogą stemplować czasu odpowiedzi.
        if (direction == CommDirection.OUTBOUND && !AutomatedMailDetector.isAutomated(parsed.headers)) {
            // Wiadomość jest ważniejsza niż księgowanie leada: jej import nie może paść
            // przez potknięcie po tamtej stronie, bo w kolejnym przebiegu zostałaby
            // pominięta po UID-zie i zniknęła ze skrzynki w CRM-ie.
            //
            // Samo `runCatching` tego NIE zapewniało i nie zapewnia: nasłuch wpięty w tę
            // samą transakcję oznaczał ją jako rollback-only, a takiej decyzji nie da
            // się już złapać — zapis wiadomości przepadał przy zatwierdzaniu. Dlatego
            // rozdzielenie jest po stronie nasłuchu: [pl.detailing.crm.leads.update.LeadFirstResponseListener]
            // biegnie PO zatwierdzeniu tej transakcji i asynchronicznie, tak jak
            // automaty leadów karmione pocztą przychodzącą. Tu zostaje tylko tarcza na
            // awarię samej publikacji.
            runCatching {
                eventPublisher.publishEvent(
                    CommOutboundSentEvent(
                        studioId = account.studioId,
                        threadId = thread.id,
                        sentAt = message.sentAt
                    )
                )
            }.onFailure {
                log.error(
                    "Nie udało się odnotować odpowiedzi spoza CRM-a | thread={} message={}: {}",
                    thread.id, message.id, it.message, it
                )
            }
        }

        // Zwrot serwera nie jest niczyim zapytaniem — automaty leadów nie mają go czytać.
        if (direction == CommDirection.INBOUND && route != InboundRoute.DeliveryReport) {
            eventPublisher.publishEvent(
                CommInboundMessageStoredEvent(
                    studioId = account.studioId,
                    accountId = account.id,
                    threadId = thread.id,
                    messageId = message.id,
                    fromEmail = parsed.fromEmail,
                    sentAt = parsed.sentAt,
                    // Ostatni moment, w którym nagłówki jeszcze istnieją — dalej zostaje
                    // po nich tylko ten boolean. Automatyczna klasyfikacja leadów odsiewa
                    // po nim newslettery, zanim zapłaci za nie modelowi.
                    automated = AutomatedMailDetector.isAutomated(parsed.headers),
                    formSubmission = thread.kind == CommThreadKind.FORM &&
                        (route is InboundRoute.FormRelay || route is InboundRoute.FormRobot),
                    fromOwnMailbox = book.isOwn(parsed.fromEmail)
                )
            )
        }
        log.debug(
            "[COMMS] Ingested {} message {} into thread {} (account {})",
            direction, messageIdHdr, thread.id, account.emailAddress
        )
        return true
    }

    private fun participantOf(
        account: MailAccountEntity,
        parsed: ParsedEmail,
        direction: CommDirection
    ): String {
        val own = account.emailAddress.lowercase()
        return if (direction == CommDirection.INBOUND) {
            parsed.fromEmail
        } else {
            parsed.toEmails.firstOrNull { it != own } ?: parsed.toEmails.firstOrNull() ?: own
        }
    }

    /**
     * Threading cascade: RFC 5322 ancestry (References / In-Reply-To) first, then
     * normalised subject + same participant within the recency window, else new thread.
     *
     * Dwa wyjątki od kaskady, oba przez formularze na stronach studiów:
     *
     *  • ZGŁOSZENIE Z FORMULARZA zawsze zaczyna własny wątek. Nie jest odpowiedzią na nic,
     *    a jego temat i nadawca są wspólne dla wszystkich zgłoszeń — dopasowanie po
     *    temacie skleiło u jednego studia 38 różnych klientów w jedną rozmowę. Jedyny
     *    powód, by dopisać je do istniejącego wątku, to duplikat: ta sama osoba wysłała
     *    formularz drugi raz, zanim zdążyliśmy odpisać.
     *
     *  • ZWROT SERWERA POCZTOWEGO idzie do wątku systemowego skrzynki. Niesie w
     *    `References` identyfikator maila, którego dotyczy, więc po przodkach wpinał się
     *    w rozmowę z klientem. Z tego samego powodu wątek systemowy nigdy nie jest celem
     *    dopasowania po przodkach dla innych wiadomości (przekazanie zwrotu dalej to już
     *    zwykła korespondencja).
     *
     * Dopasowanie po temacie odpada też wtedy, gdy drugą stroną jest adres po naszej
     * stronie (skrzynka studia, robot formularza): dla takiego adresu temat nie
     * odróżnia rozmów, bo wszystkie jego maile mają ten sam.
     */
    private fun resolveThread(
        account: MailAccountEntity,
        parsed: ParsedEmail,
        route: InboundRoute?,
        book: MailAddressBook,
        participantEmail: String,
        participantName: String?
    ): CommThreadEntity {
        when (route) {
            InboundRoute.DeliveryReport -> return systemThread(account, parsed)
            is InboundRoute.FormRelay -> {
                val since = parsed.sentAt.minus(DUPLICATE_SUBMISSION_WINDOW_HOURS, ChronoUnit.HOURS)
                threadRepository
                    .findRecentFormThreads(account.id, participantEmail, route.relayEmail, since)
                    .firstOrNull { it.outboundCount == 0 }
                    ?.let { return it }
                return createThread(
                    account, parsed, participantEmail, participantName,
                    kind = CommThreadKind.FORM, relayEmail = route.relayEmail
                )
            }
            // Klienta poznamy dopiero z treści — do tego czasu drugą stroną jest robot,
            // a procesor formularza przepnie wątek po odczycie.
            is InboundRoute.FormRobot -> return createThread(
                account, parsed, participantEmail, participantName,
                kind = CommThreadKind.FORM, relayEmail = route.relayEmail
            )
            else -> Unit
        }

        val ancestry = (parsed.references + listOfNotNull(parsed.inReplyTo)).distinct()
        if (ancestry.isNotEmpty()) {
            val relatives = messageRepository.findByAccountIdAndMessageIdHdrIn(account.id, ancestry)
            for (relative in relatives) {
                val relativeThread = threadRepository.findById(relative.threadId).orElse(null) ?: continue
                if (relativeThread.kind != CommThreadKind.SYSTEM) return relativeThread
            }
        }

        val subjectNorm = normalizeSubject(parsed.subject)
        if (subjectNorm != null && !book.isNotAClient(participantEmail)) {
            val since = Instant.now().minus(SUBJECT_MATCH_WINDOW_DAYS, ChronoUnit.DAYS)
            threadRepository
                .findRecentBySubjectAndParticipant(account.id, subjectNorm, participantEmail, since)
                .firstOrNull { it.kind != CommThreadKind.SYSTEM }
                ?.let { return it }
        }

        return createThread(account, parsed, participantEmail, participantName)
    }

    /**
     * Wątek zwrotów serwera pocztowego — jeden na skrzynkę, od razu w archiwum: zwroty
     * nie mają nic do zrobienia w „Odebranych" ani w liczniku nieprzeczytanych, ale
     * zostają do wglądu, gdy ktoś zapyta „czemu klient nie dostał maila".
     */
    private fun systemThread(account: MailAccountEntity, parsed: ParsedEmail): CommThreadEntity =
        threadRepository.findFirstByAccountIdAndKindOrderByCreatedAtAsc(account.id, CommThreadKind.SYSTEM)
            ?: threadRepository.save(
                CommThreadEntity(
                    id = UUID.randomUUID(),
                    studioId = account.studioId,
                    accountId = account.id,
                    subjectNorm = null,
                    subject = SYSTEM_THREAD_SUBJECT,
                    participantEmail = MailAddressBook.normalize(parsed.fromEmail),
                    participantName = SYSTEM_THREAD_PARTICIPANT,
                    lastMessageAt = parsed.sentAt,
                    lastDirection = CommDirection.INBOUND,
                    lastSnippet = null,
                    leadId = null,
                    labelId = null,
                    archived = true,
                    kind = CommThreadKind.SYSTEM
                )
            )

    private fun createThread(
        account: MailAccountEntity,
        parsed: ParsedEmail,
        participantEmail: String,
        participantName: String?,
        kind: CommThreadKind = CommThreadKind.DIRECT,
        relayEmail: String? = null
    ): CommThreadEntity = threadRepository.save(
        CommThreadEntity(
            id = UUID.randomUUID(),
            studioId = account.studioId,
            accountId = account.id,
            subjectNorm = normalizeSubject(parsed.subject),
            subject = parsed.subject?.take(1000),
            participantEmail = participantEmail,
            participantName = participantName,
            lastMessageAt = parsed.sentAt,
            lastDirection = CommDirection.INBOUND,
            lastSnippet = null,
            leadId = null,
            labelId = null,
            kind = kind,
            relayEmail = relayEmail
        )
    )

    private fun refreshThreadAggregates(
        thread: CommThreadEntity,
        message: CommMessageEntity,
        participantName: String?,
        route: InboundRoute?
    ) {
        thread.messageCount += 1
        if (message.direction == CommDirection.INBOUND) thread.inboundCount += 1 else thread.outboundCount += 1
        if (!message.isRead) thread.unreadCount += 1
        if (message.hasAttachments) thread.hasAttachments = true
        if (participantName != null && thread.participantName == null) {
            thread.participantName = participantName
        }
        // Backfill arrives out of order — only a genuinely newer message moves the head.
        if (!message.sentAt.isBefore(thread.lastMessageAt) || thread.messageCount == 1) {
            thread.lastMessageAt = message.sentAt
            thread.lastDirection = message.direction
            thread.lastSnippet = message.bodyTextClean
                ?.replace(Regex("\\s+"), " ")
                ?.take(200)
        }
        if (thread.subject == null && message.subject != null) {
            thread.subject = message.subject
            thread.subjectNorm = normalizeSubject(message.subject)
        }
        // Werdykt „spam / test" dotyczył zgłoszenia. Odpis klienta albo nasza odpowiedź
        // znaczą, że to jednak rozmowa — wątek wraca do Odebranych bez klikania.
        if (thread.screening != null &&
            (message.direction == CommDirection.OUTBOUND || route == InboundRoute.Direct)
        ) {
            thread.screening = null
            thread.screeningReason = null
        }
        threadRepository.save(thread)
    }

    /**
     * Some senders omit Message-ID. A deterministic synthetic id built from immutable
     * message facts keeps the dedupe idempotent across re-fetches.
     */
    private fun syntheticMessageId(accountId: UUID, parsed: ParsedEmail): String {
        val basis = "${parsed.fromEmail}|${parsed.sentAt.epochSecond}|${parsed.subject.orEmpty().take(200)}"
        return "synthetic-${UUID.nameUUIDFromBytes("$accountId|$basis".toByteArray())}@crm.local"
    }

    companion object {
        const val SUBJECT_MATCH_WINDOW_DAYS = 30L

        /**
         * Ile czasu drugie zgłoszenie tej samej osoby przez ten sam formularz dopisuje się
         * do pierwszego (o ile nikt jeszcze nie odpisał). Klient poprawiający treść wysyła
         * formularz ponownie po kilku minutach — to jedna sprawa, nie dwa leady.
         */
        const val DUPLICATE_SUBMISSION_WINDOW_HOURS = 48L

        const val SYSTEM_THREAD_SUBJECT = "Zwroty i powiadomienia serwera poczty"
        const val SYSTEM_THREAD_PARTICIPANT = "Serwer poczty"

        /** Temat bez „Re:/Fwd:/Odp:", małymi literami — klucz dopasowania po temacie. */
        fun normalizeSubject(subject: String?): String? {
            if (subject.isNullOrBlank()) return null
            var value = subject.trim()
            var changed = true
            while (changed) {
                changed = false
                for (prefix in SUBJECT_PREFIXES) {
                    if (value.startsWith(prefix, ignoreCase = true)) {
                        value = value.removeRange(0, prefix.length).trim()
                        changed = true
                    }
                }
            }
            return value.lowercase().take(500).takeIf { it.isNotBlank() }
        }

        private val SUBJECT_PREFIXES = listOf("re:", "odp:", "odp.:", "fwd:", "fw:", "pd:")
    }
}
