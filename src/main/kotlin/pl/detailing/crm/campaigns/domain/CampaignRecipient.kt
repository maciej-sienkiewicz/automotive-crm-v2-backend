package pl.detailing.crm.campaigns.domain

import java.time.Instant
import java.util.UUID

enum class RecipientChannel { SMS, EMAIL }

enum class RecipientStatus {
    PENDING, SENT, FAILED,
    SKIPPED_NO_CONSENT, SKIPPED_NO_ADDRESS, SKIPPED_FREQUENCY_CAP,
    SKIPPED_OPTED_OUT, EXCLUDED_MANUALLY, SKIPPED_NO_CREDITS,
    STOPPED
}

data class CampaignRecipient(
    val id: UUID,
    val campaignId: UUID,
    val studioId: UUID,
    val customerId: UUID,
    val channel: RecipientChannel,
    val address: String,
    val renderedSubject: String?,
    val renderedBody: String,
    val status: RecipientStatus,
    val errorMessage: String? = null,
    val triggerSourceVisitId: UUID? = null,
    val scheduledFor: Instant,
    val sentAt: Instant? = null,
    val createdAt: Instant
)
