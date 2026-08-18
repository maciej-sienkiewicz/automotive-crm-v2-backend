package pl.detailing.crm.mailbox

import pl.detailing.crm.mailbox.infrastructure.MailAccountEntity
import pl.detailing.crm.mailbox.infrastructure.MailProviderDetection
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
