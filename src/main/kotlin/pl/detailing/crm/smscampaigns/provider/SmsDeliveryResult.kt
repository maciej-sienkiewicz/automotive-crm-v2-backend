package pl.detailing.crm.smscampaigns.provider

import java.time.Instant
import java.util.UUID

/**
 * Immutable result of a single SMS dispatch attempt.
 * Keeps the [SmsProvider] contract free of checked exceptions.
 *
 * A message accepted for later delivery (outside the customer send window) is a success
 * from the caller's point of view — the studio has done its part — but carries
 * [queuedMessageId] and [scheduledFor] so the log and the UI can say "wyjdzie o 12:00"
 * instead of "wysłano". [externalMessageId] is null until the dispatcher actually sends it.
 */
data class SmsDeliveryResult(
    val success: Boolean,
    val externalMessageId: String?,
    val errorMessage: String?,
    val queuedMessageId: UUID? = null,
    val scheduledFor: Instant? = null
) {
    val queued: Boolean get() = queuedMessageId != null

    companion object {
        fun success(externalMessageId: String) = SmsDeliveryResult(
            success = true,
            externalMessageId = externalMessageId,
            errorMessage = null
        )

        fun failure(errorMessage: String) = SmsDeliveryResult(
            success = false,
            externalMessageId = null,
            errorMessage = errorMessage
        )

        fun queued(queuedMessageId: UUID, scheduledFor: Instant) = SmsDeliveryResult(
            success = true,
            externalMessageId = null,
            errorMessage = null,
            queuedMessageId = queuedMessageId,
            scheduledFor = scheduledFor
        )
    }
}
