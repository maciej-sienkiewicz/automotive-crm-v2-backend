package pl.detailing.crm.mailbox

import pl.detailing.crm.mailbox.infrastructure.MailAccountEntity
import pl.detailing.crm.mailbox.infrastructure.MailProviderDetection
import pl.detailing.crm.mailbox.thread.LeadThreadView
import pl.detailing.crm.mailbox.thread.ReviewQueueItem
import java.time.Instant
import java.util.UUID

data class DetectProviderRequest(val email: String)

data class DetectProviderResponse(
    val providerType: String,
    val authType: String,
    val imapHost: String?,
    val imapPort: Int?,
    val smtpHost: String?,
    val smtpPort: Int?,
    val requiresAppPassword: Boolean,
    val guideUrl: String?
)

data class ConnectMailAccountRequest(
    val email: String,
    val password: String,
    val imapHost: String? = null,
    val imapPort: Int? = null,
    val smtpHost: String? = null,
    val smtpPort: Int? = null
)

/** Credentials never leave the server — the response carries connection state only. */
data class MailAccountResponse(
    val id: UUID,
    val emailAddress: String,
    val providerType: String,
    val status: String,
    val lastError: String?,
    val lastSyncAt: Instant?
)

data class SendReplyRequest(val bodyHtml: String)

data class SendReplyResponse(val messageId: UUID, val leadId: UUID?)

data class ThreadMessageResponse(
    val id: UUID,
    val direction: String,
    val fromEmail: String,
    val fromName: String?,
    val subject: String?,
    val sentAt: Instant,
    val bodyText: String,
    val hasAttachments: Boolean,
    val sendStatus: String
)

data class LeadThreadResponse(
    /** Null when the lead has no e-mail conversation — an empty state for the UI, not an error. */
    val threadId: UUID?,
    val leadId: UUID?,
    val classification: String?,
    val lastMessageAt: Instant?,
    val lastDirection: String?,
    val canReplyFromCrm: Boolean,
    val messages: List<ThreadMessageResponse>
)

data class ReviewQueueItemResponse(
    val threadId: UUID,
    val fromEmail: String,
    val fromName: String?,
    val subject: String?,
    val snippet: String,
    val receivedAt: Instant
)

data class ReviewDecisionRequest(val decision: String)

fun MailAccountEntity.toResponse() = MailAccountResponse(
    id = id,
    emailAddress = emailAddress,
    providerType = providerType.name,
    status = status.name,
    lastError = lastError,
    lastSyncAt = lastSyncAt
)

fun MailProviderDetection.toResponse() = DetectProviderResponse(
    providerType = providerType.name,
    authType = authType.name,
    imapHost = imapHost,
    imapPort = imapPort,
    smtpHost = smtpHost,
    smtpPort = smtpPort,
    requiresAppPassword = requiresAppPassword,
    guideUrl = guideUrl
)

fun LeadThreadView.toResponse() = LeadThreadResponse(
    threadId = threadId,
    leadId = leadId,
    classification = classification,
    lastMessageAt = lastMessageAt,
    lastDirection = lastDirection,
    canReplyFromCrm = canReplyFromCrm,
    messages = messages.map {
        ThreadMessageResponse(
            id = it.id,
            direction = it.direction,
            fromEmail = it.fromEmail,
            fromName = it.fromName,
            subject = it.subject,
            sentAt = it.sentAt,
            bodyText = it.bodyText,
            hasAttachments = it.hasAttachments,
            sendStatus = it.sendStatus
        )
    }
)

fun ReviewQueueItem.toResponse() = ReviewQueueItemResponse(
    threadId = threadId,
    fromEmail = fromEmail,
    fromName = fromName,
    subject = subject,
    snippet = snippet,
    receivedAt = receivedAt
)
