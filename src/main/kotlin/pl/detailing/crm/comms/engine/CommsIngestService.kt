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
import pl.detailing.crm.comms.domain.EmailTextCleaner
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
    private val eventPublisher: ApplicationEventPublisher
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
        val participantEmail = participantOf(account, parsed, direction)
        val participantName =
            if (direction == CommDirection.INBOUND) parsed.fromName else null

        val thread = resolveThread(account, parsed, participantEmail, participantName)
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
            sendStatus = if (direction == CommDirection.OUTBOUND) CommSendStatus.SENT else CommSendStatus.RECEIVED
        )
        messageRepository.save(message)

        refreshThreadAggregates(thread, message, participantName)

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
                newMessage = !backfill && direction == CommDirection.INBOUND
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
            // Nasłuch (status leada) biegnie w tej samej transakcji co zapis wiadomości,
            // a wiadomość jest ważniejsza: jej import nie może paść przez potknięcie
            // w księgowaniu leada, bo w kolejnym przebiegu i tak zostałaby pominięta
            // po UID-zie i zniknęła ze skrzynki w CRM-ie.
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

        if (direction == CommDirection.INBOUND) {
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
                    automated = AutomatedMailDetector.isAutomated(parsed.headers)
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
     */
    private fun resolveThread(
        account: MailAccountEntity,
        parsed: ParsedEmail,
        participantEmail: String,
        participantName: String?
    ): CommThreadEntity {
        val ancestry = (parsed.references + listOfNotNull(parsed.inReplyTo)).distinct()
        if (ancestry.isNotEmpty()) {
            val relative = messageRepository
                .findByAccountIdAndMessageIdHdrIn(account.id, ancestry)
                .firstOrNull()
            if (relative != null) {
                return threadRepository.findById(relative.threadId).orElse(null)
                    ?: createThread(account, parsed, participantEmail, participantName)
            }
        }

        val subjectNorm = normalizeSubject(parsed.subject)
        if (subjectNorm != null) {
            val since = Instant.now().minus(SUBJECT_MATCH_WINDOW_DAYS, ChronoUnit.DAYS)
            threadRepository
                .findRecentBySubjectAndParticipant(account.id, subjectNorm, participantEmail, since)
                .firstOrNull()
                ?.let { return it }
        }

        return createThread(account, parsed, participantEmail, participantName)
    }

    private fun createThread(
        account: MailAccountEntity,
        parsed: ParsedEmail,
        participantEmail: String,
        participantName: String?
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
            labelId = null
        )
    )

    private fun refreshThreadAggregates(
        thread: CommThreadEntity,
        message: CommMessageEntity,
        participantName: String?
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
        threadRepository.save(thread)
    }

    private fun normalizeSubject(subject: String?): String? {
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

    /**
     * Some senders omit Message-ID. A deterministic synthetic id built from immutable
     * message facts keeps the dedupe idempotent across re-fetches.
     */
    private fun syntheticMessageId(accountId: UUID, parsed: ParsedEmail): String {
        val basis = "${parsed.fromEmail}|${parsed.sentAt.epochSecond}|${parsed.subject.orEmpty().take(200)}"
        return "synthetic-${UUID.nameUUIDFromBytes("$accountId|$basis".toByteArray())}@crm.local"
    }

    private companion object {
        const val SUBJECT_MATCH_WINDOW_DAYS = 30L
        val SUBJECT_PREFIXES = listOf("re:", "odp:", "odp.:", "fwd:", "fw:", "pd:")
    }
}
