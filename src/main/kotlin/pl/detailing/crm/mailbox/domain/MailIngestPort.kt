package pl.detailing.crm.mailbox.domain

import java.time.Instant
import java.util.UUID

/**
 * Transport-agnostic view of a single e-mail, produced by IMAP sync, the Gmail/Graph pollers
 * or the Cloudflare webhook. Everything downstream (threading, classification, denoising)
 * works on this shape only.
 */
data class RawIncomingEmail(
    /** Normalised Message-ID without angle brackets; null when the sender omitted the header. */
    val messageId: String?,
    val inReplyTo: String?,
    val references: List<String>,
    /** Provider-native conversation id (Gmail threadId, Graph conversationId) when available. */
    val externalThreadKey: String? = null,
    val fromEmail: String,
    val fromName: String?,
    val toEmails: List<String>,
    val subject: String?,
    val sentAt: Instant,
    val bodyHtml: String?,
    val bodyText: String?,
    val hasAttachments: Boolean = false,
    val providerUid: String? = null,
    val direction: MailDirection,
    /** Lowercase keys, limited to: list-unsubscribe, list-id, auto-submitted, precedence. */
    val headers: Map<String, String> = emptyMap()
)

interface MailIngestPort {
    suspend fun ingest(studioId: UUID, mailAccountId: UUID?, email: RawIncomingEmail)
}
