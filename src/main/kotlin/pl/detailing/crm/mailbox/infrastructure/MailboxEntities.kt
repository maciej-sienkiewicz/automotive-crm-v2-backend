package pl.detailing.crm.mailbox.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import pl.detailing.crm.mailbox.domain.MailAccountStatus
import pl.detailing.crm.mailbox.domain.MailAuthType
import pl.detailing.crm.mailbox.domain.MailDirection
import pl.detailing.crm.mailbox.domain.MailProviderType
import pl.detailing.crm.mailbox.domain.MailSendStatus
import pl.detailing.crm.mailbox.domain.MailThreadClassification
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "mail_accounts",
    indexes = [
        Index(name = "idx_mail_accounts_studio_email", columnList = "studio_id, email_address", unique = true),
        Index(name = "idx_mail_accounts_studio", columnList = "studio_id"),
        Index(name = "idx_mail_accounts_status", columnList = "status")
    ]
)
class MailAccountEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "email_address", nullable = false, length = 320)
    val emailAddress: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type", nullable = false, length = 30)
    var providerType: MailProviderType,

    @Enumerated(EnumType.STRING)
    @Column(name = "auth_type", nullable = false, length = 30)
    var authType: MailAuthType,

    /** AES-GCM ciphertext produced by MailboxEncryptionService — never logged, never returned by the API. */
    @Column(name = "encrypted_password", columnDefinition = "text")
    var encryptedPassword: String?,

    @Column(name = "imap_host", length = 255)
    var imapHost: String?,

    @Column(name = "imap_port")
    var imapPort: Int?,

    @Column(name = "smtp_host", length = 255)
    var smtpHost: String?,

    @Column(name = "smtp_port")
    var smtpPort: Int?,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    var status: MailAccountStatus,

    @Column(name = "last_error", length = 500)
    var lastError: String?,

    @Column(name = "last_sync_at")
    var lastSyncAt: Instant?,

    @Column(name = "inbox_uid_validity")
    var inboxUidValidity: Long?,

    @Column(name = "inbox_last_uid")
    var inboxLastUid: Long?,

    @Column(name = "sent_uid_validity")
    var sentUidValidity: Long?,

    @Column(name = "sent_last_uid")
    var sentLastUid: Long?,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)

@Entity
@Table(
    name = "mail_threads",
    indexes = [
        Index(name = "idx_mail_threads_studio_last_msg", columnList = "studio_id, last_message_at"),
        Index(name = "idx_mail_threads_lead", columnList = "lead_id"),
        Index(name = "idx_mail_threads_studio_subject", columnList = "studio_id, subject_norm"),
        Index(name = "idx_mail_threads_studio_extkey", columnList = "studio_id, external_thread_key"),
        Index(name = "idx_mail_threads_studio_classification", columnList = "studio_id, classification")
    ]
)
class MailThreadEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    /** Null for threads ingested through the Cloudflare webhook, which has no mailbox behind it. */
    @Column(name = "mail_account_id", columnDefinition = "uuid")
    var mailAccountId: UUID?,

    /** Set once the thread is recognised as a sales inquiry; spam and invoices stay lead-less. */
    @Column(name = "lead_id", columnDefinition = "uuid")
    var leadId: UUID?,

    @Column(name = "subject_norm", length = 500)
    var subjectNorm: String?,

    @Column(name = "external_thread_key", length = 255)
    var externalThreadKey: String?,

    @Enumerated(EnumType.STRING)
    @Column(name = "classification", nullable = false, length = 20)
    var classification: MailThreadClassification,

    @Column(name = "last_message_at", nullable = false)
    var lastMessageAt: Instant,

    @Enumerated(EnumType.STRING)
    @Column(name = "last_direction", length = 10)
    var lastDirection: MailDirection?,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)

@Entity
@Table(
    name = "mail_messages",
    indexes = [
        Index(name = "idx_mail_messages_studio_msgid", columnList = "studio_id, message_id", unique = true),
        Index(name = "idx_mail_messages_thread_sent", columnList = "thread_id, sent_at")
    ]
)
class MailMessageEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "thread_id", nullable = false, columnDefinition = "uuid")
    val threadId: UUID,

    @Column(name = "mail_account_id", columnDefinition = "uuid")
    var mailAccountId: UUID?,

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10)
    val direction: MailDirection,

    /** RFC 5322 Message-ID, normalised without angle brackets. */
    @Column(name = "message_id", nullable = false, length = 500)
    val messageId: String,

    @Column(name = "in_reply_to", length = 500)
    val inReplyTo: String?,

    /** Space-separated Message-IDs from the References header, oldest first. */
    @Column(name = "references_ids", columnDefinition = "text")
    val referencesIds: String?,

    @Column(name = "from_email", nullable = false, length = 320)
    val fromEmail: String,

    @Column(name = "from_name", length = 255)
    val fromName: String?,

    @Column(name = "to_emails", columnDefinition = "text")
    val toEmails: String?,

    @Column(name = "subject", length = 1000)
    val subject: String?,

    @Column(name = "sent_at", nullable = false)
    val sentAt: Instant,

    @Column(name = "body_html", columnDefinition = "text")
    val bodyHtml: String?,

    /** Denoised body: quoted history and signatures already removed. This is what the UI and the LLM read. */
    @Column(name = "body_text_clean", columnDefinition = "text")
    var bodyTextClean: String?,

    @Column(name = "has_attachments", nullable = false)
    val hasAttachments: Boolean = false,

    @Column(name = "provider_uid", length = 255)
    var providerUid: String?,

    @Enumerated(EnumType.STRING)
    @Column(name = "send_status", nullable = false, length = 20)
    var sendStatus: MailSendStatus,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)

@Entity
@Table(
    name = "mail_sender_rules",
    indexes = [
        Index(name = "idx_mail_sender_rules_studio_pattern", columnList = "studio_id, pattern", unique = true)
    ]
)
class MailSenderRuleEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    /** Full e-mail address or a bare domain. */
    @Column(name = "pattern", nullable = false, length = 320)
    val pattern: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "classification", nullable = false, length = 20)
    val classification: MailThreadClassification,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)
