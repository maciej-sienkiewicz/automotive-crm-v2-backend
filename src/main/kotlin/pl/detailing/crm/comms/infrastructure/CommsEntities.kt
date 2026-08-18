package pl.detailing.crm.comms.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import pl.detailing.crm.comms.domain.CommDirection
import pl.detailing.crm.comms.domain.CommFolderKind
import pl.detailing.crm.comms.domain.CommOutboxStatus
import pl.detailing.crm.comms.domain.CommOutboxType
import pl.detailing.crm.comms.domain.CommReadSource
import pl.detailing.crm.comms.domain.CommSendStatus
import java.time.Instant
import java.util.UUID

/**
 * One conversation. Threads are the unit the user works with: the list shows threads,
 * a lead points at a thread, and follow-up messages join their thread automatically.
 */
@Entity
@Table(
    name = "comm_threads",
    indexes = [
        Index(name = "idx_comm_threads_studio_last", columnList = "studio_id, last_message_at"),
        Index(name = "idx_comm_threads_account_last", columnList = "account_id, last_message_at"),
        Index(name = "idx_comm_threads_participant", columnList = "studio_id, participant_email"),
        Index(name = "idx_comm_threads_lead", columnList = "lead_id"),
        Index(name = "idx_comm_threads_label", columnList = "label_id")
    ]
)
class CommThreadEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "account_id", nullable = false, columnDefinition = "uuid")
    val accountId: UUID,

    /** Normalised subject (Re:/Fwd: stripped, lowercased) — last resort of the threading cascade. */
    @Column(name = "subject_norm", length = 500)
    var subjectNorm: String?,

    /** Original subject of the first message, shown in the list. */
    @Column(name = "subject", length = 1000)
    var subject: String?,

    /** The external side of the conversation (never our own mailbox address). */
    @Column(name = "participant_email", nullable = false, length = 320)
    var participantEmail: String,

    @Column(name = "participant_name", length = 255)
    var participantName: String?,

    @Column(name = "last_message_at", nullable = false)
    var lastMessageAt: Instant,

    @Enumerated(EnumType.STRING)
    @Column(name = "last_direction", nullable = false, length = 10)
    var lastDirection: CommDirection,

    /** First ~160 chars of the newest message, pre-computed for the thread list. */
    @Column(name = "last_snippet", length = 200)
    var lastSnippet: String?,

    @Column(name = "message_count", nullable = false)
    var messageCount: Int = 0,

    @Column(name = "unread_count", nullable = false)
    var unreadCount: Int = 0,

    @Column(name = "has_attachments", nullable = false)
    var hasAttachments: Boolean = false,

    /** Set when the thread is promoted to a lead; stays after the lead closes. */
    @Column(name = "lead_id", columnDefinition = "uuid")
    var leadId: UUID?,

    /** Local, user-defined folder. Purely our side — never synced to IMAP. */
    @Column(name = "label_id", columnDefinition = "uuid")
    var labelId: UUID?,

    @Column(name = "archived", nullable = false)
    var archived: Boolean = false,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)

@Entity
@Table(
    name = "comm_messages",
    indexes = [
        Index(name = "idx_comm_messages_account_msgid", columnList = "account_id, message_id_hdr", unique = true),
        Index(name = "idx_comm_messages_thread_sent", columnList = "thread_id, sent_at"),
        Index(name = "idx_comm_messages_studio_from", columnList = "studio_id, from_email"),
        Index(name = "idx_comm_messages_unread", columnList = "account_id, folder_kind, is_read")
    ]
)
class CommMessageEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "account_id", nullable = false, columnDefinition = "uuid")
    val accountId: UUID,

    @Column(name = "thread_id", nullable = false, columnDefinition = "uuid")
    val threadId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10)
    val direction: CommDirection,

    @Enumerated(EnumType.STRING)
    @Column(name = "folder_kind", nullable = false, length = 10)
    val folderKind: CommFolderKind,

    /** RFC 5322 Message-ID without angle brackets; ingest idempotency key per account. */
    @Column(name = "message_id_hdr", nullable = false, length = 500)
    val messageIdHdr: String,

    @Column(name = "in_reply_to", length = 500)
    val inReplyTo: String?,

    /** Space-separated Message-IDs from References, oldest first. */
    @Column(name = "references_ids", columnDefinition = "text")
    val referencesIds: String?,

    @Column(name = "from_email", nullable = false, length = 320)
    val fromEmail: String,

    @Column(name = "from_name", length = 255)
    val fromName: String?,

    @Column(name = "to_emails", columnDefinition = "text")
    val toEmails: String?,

    @Column(name = "cc_emails", columnDefinition = "text")
    val ccEmails: String?,

    @Column(name = "subject", length = 1000)
    val subject: String?,

    @Column(name = "sent_at", nullable = false)
    val sentAt: Instant,

    /**
     * Server-side sanitised HTML: scripts/handlers stripped, cid: images rewritten to our
     * attachment endpoint. The frontend still renders it inside a sandboxed iframe.
     */
    @Column(name = "body_html_safe", columnDefinition = "text")
    var bodyHtmlSafe: String?,

    @Column(name = "body_text", columnDefinition = "text")
    var bodyText: String?,

    /** Denoised plain text (quoted history and signature stripped) for snippets and insights. */
    @Column(name = "body_text_clean", columnDefinition = "text")
    var bodyTextClean: String?,

    @Column(name = "has_attachments", nullable = false)
    val hasAttachments: Boolean = false,

    /** IMAP identity, only meaningful while (account, folder) keeps its UIDVALIDITY. */
    @Column(name = "imap_uid")
    var imapUid: Long?,

    @Column(name = "imap_uid_validity")
    var imapUidValidity: Long?,

    @Column(name = "is_read", nullable = false)
    var isRead: Boolean = false,

    @Enumerated(EnumType.STRING)
    @Column(name = "read_source", length = 10)
    var readSource: CommReadSource?,

    @Column(name = "read_at")
    var readAt: Instant?,

    @Enumerated(EnumType.STRING)
    @Column(name = "send_status", nullable = false, length = 10)
    var sendStatus: CommSendStatus,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)

/**
 * Attachment metadata + content. Content lives in Postgres: mailbox attachments are
 * small (hard 15 MB guard at ingest) and this keeps the module free of extra
 * infrastructure; the bytes are fetched lazily and never loaded for list views.
 */
@Entity
@Table(
    name = "comm_attachments",
    indexes = [
        Index(name = "idx_comm_attachments_message", columnList = "message_id"),
        Index(name = "idx_comm_attachments_cid", columnList = "message_id, content_id")
    ]
)
class CommAttachmentEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "message_id", nullable = false, columnDefinition = "uuid")
    val messageId: UUID,

    @Column(name = "file_name", nullable = false, length = 500)
    val fileName: String,

    @Column(name = "content_type", nullable = false, length = 255)
    val contentType: String,

    /** MIME Content-ID without angle brackets, set for inline (cid:) images. */
    @Column(name = "content_id", length = 300)
    val contentId: String?,

    @Column(name = "is_inline", nullable = false)
    val isInline: Boolean,

    @Column(name = "size_bytes", nullable = false)
    val sizeBytes: Long,

    // Celowo bez @Lob: Hibernate 6 mapowałby ByteArray na Postgresowe large objects
    // (oid), a schemat deklaruje bytea. Bajty nie trafiają do widoków list dzięki
    // projekcji CommAttachmentMeta, więc lazy-loading kolumny nie jest potrzebny.
    @Column(name = "content", nullable = false, columnDefinition = "bytea")
    val content: ByteArray
)

/** User-defined local folder ("label"). Exists only in the CRM, never touches IMAP. */
@Entity
@Table(
    name = "comm_labels",
    indexes = [
        Index(name = "idx_comm_labels_studio_name", columnList = "studio_id, name", unique = true)
    ]
)
class CommLabelEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "name", nullable = false, length = 100)
    var name: String,

    @Column(name = "color", length = 20)
    var color: String?,

    @Column(name = "position", nullable = false)
    var position: Int = 0,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)

/**
 * Queued IMAP side effects of user actions (mark read, append to Sent). The UI already
 * shows the optimistic result; this queue makes the server converge, with retries.
 */
@Entity
@Table(
    name = "comm_outbox",
    indexes = [
        Index(name = "idx_comm_outbox_due", columnList = "status, next_attempt_at"),
        Index(name = "idx_comm_outbox_message", columnList = "message_id")
    ]
)
class CommOutboxEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "account_id", nullable = false, columnDefinition = "uuid")
    val accountId: UUID,

    @Column(name = "message_id", nullable = false, columnDefinition = "uuid")
    val messageId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "command_type", nullable = false, length = 20)
    val commandType: CommOutboxType,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    var status: CommOutboxStatus,

    @Column(name = "attempts", nullable = false)
    var attempts: Int = 0,

    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: Instant,

    @Column(name = "last_error", length = 500)
    var lastError: String?,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)
