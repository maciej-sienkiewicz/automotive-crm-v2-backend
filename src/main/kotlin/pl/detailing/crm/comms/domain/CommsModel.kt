package pl.detailing.crm.comms.domain

import java.time.Instant
import java.util.UUID

/**
 * Which mailbox folder a message was ingested from. Only these two are synced —
 * everything else the user organises locally with labels, so a message can never
 * "disappear" because somebody re-arranged folders on the server.
 */
enum class CommFolderKind {
    INBOX,
    SENT
}

enum class CommDirection {
    INBOUND,
    OUTBOUND
}

/**
 * Who flipped the message to read first. EXTERNAL means the \Seen flag arrived from
 * the IMAP server (phone, webmail…), CRM means one of our users opened it here.
 */
enum class CommReadSource {
    CRM,
    EXTERNAL
}

enum class CommSendStatus {
    RECEIVED,
    SENDING,
    SENT,
    FAILED
}

/** Asynchronous IMAP work queued by user actions; each command is idempotent. */
enum class CommOutboxType {
    /** STORE +FLAGS (\Seen) for one message. */
    MARK_SEEN,
    /** APPEND a copy of a message we sent over SMTP into the server's Sent folder. */
    APPEND_SENT
}

enum class CommOutboxStatus {
    PENDING,
    DONE,
    FAILED
}

/** One attachment extracted from MIME, inline images (cid:) included. */
data class ParsedAttachment(
    val fileName: String,
    val contentType: String,
    val contentId: String?,
    val inline: Boolean,
    val content: ByteArray
)

/**
 * Transport-agnostic result of parsing one MIME message. Everything downstream
 * (threading, sanitising, persistence) works on this shape only.
 */
data class ParsedEmail(
    /** Normalised Message-ID without angle brackets; null when the sender omitted it. */
    val messageId: String?,
    val inReplyTo: String?,
    val references: List<String>,
    val fromEmail: String,
    val fromName: String?,
    val toEmails: List<String>,
    val ccEmails: List<String>,
    val subject: String?,
    val sentAt: Instant,
    val bodyHtml: String?,
    val bodyText: String?,
    val attachments: List<ParsedAttachment>,
    val imapUid: Long?,
    val seen: Boolean,
    /** Lowercase keys, limited to list/auto-reply headers used for lead heuristics. */
    val headers: Map<String, String> = emptyMap()
)

/** Snapshot pushed over WebSocket when a thread gains a message or changes state. */
data class CommThreadChangedEvent(
    val studioId: UUID,
    val threadId: UUID,
    /**
     * Wątek dostał nową wiadomość PRZYCHODZĄCĄ — jedyny przypadek, w którym warto
     * zaczepić użytkownika powiadomieniem. Własna wysyłka (z CRM-a albo z telefonu,
     * zassana z folderu Wysłane) zmienia wątek, ale nie jest niczym nowym dla tego,
     * kto ją napisał: idzie z tą flagą wyłączoną i odświeża tylko listę.
     */
    val newMessage: Boolean
)

/** Pushed when a message's read state changes (either side). */
data class CommMessageReadEvent(
    val studioId: UUID,
    val threadId: UUID,
    val messageId: UUID,
    val readSource: CommReadSource
)

/** Published after a reply leaves over SMTP — feeds the lead first-response metric. */
data class CommOutboundSentEvent(
    val studioId: UUID,
    val threadId: UUID,
    val sentAt: Instant
)

/**
 * Published after a brand-new INBOUND message is committed. Carries the sender so
 * listeners (form-mail auto-leads) can decide relevance without loading the row.
 */
data class CommInboundMessageStoredEvent(
    val studioId: UUID,
    val accountId: UUID,
    val threadId: UUID,
    val messageId: UUID,
    val fromEmail: String,
    val sentAt: Instant,
    /**
     * Wiadomość przedstawiła się nagłówkami jako automat — newsletter, autoresponder,
     * powiadomienie systemowe (patrz [AutomatedMailDetector]).
     *
     * Werdykt jedzie w zdarzeniu, bo nagłówki są dostępne WYŁĄCZNIE w chwili parsowania
     * MIME: baza przechowuje treść i adresy, nie `List-Id` ani `Precedence`. Listener,
     * który chciałby je sprawdzić później, musiałby po nie wrócić na serwer IMAP.
     *
     * Klasyfikacja leadów używa tego jako darmowego pre-filtra: newsletter i tak nigdy
     * nie jest zapytaniem o wycenę, więc nie ma po co płacić za jego przeczytanie modelem.
     */
    val automated: Boolean = false
)
