package pl.detailing.crm.email.provider

import java.time.Instant
import java.util.UUID

/**
 * Result of a single e-mail dispatch attempt.
 *
 * A message accepted for later delivery (outside the customer send window) is a success
 * from the caller's point of view, but carries [queuedMessageId] and [scheduledFor] so the
 * log and the UI can say "wyjdzie o 12:00" instead of "wysłano". [messageId] is null until
 * the dispatcher actually sends it.
 */
data class EmailDeliveryResult(
    val success: Boolean,
    val messageId: String?,
    val errorMessage: String?,
    val queuedMessageId: UUID? = null,
    val scheduledFor: Instant? = null
) {
    val queued: Boolean get() = queuedMessageId != null

    companion object {
        fun success(messageId: String) = EmailDeliveryResult(
            success = true,
            messageId = messageId,
            errorMessage = null
        )

        fun failure(error: String) = EmailDeliveryResult(
            success = false,
            messageId = null,
            errorMessage = error
        )

        fun queued(queuedMessageId: UUID, scheduledFor: Instant) = EmailDeliveryResult(
            success = true,
            messageId = null,
            errorMessage = null,
            queuedMessageId = queuedMessageId,
            scheduledFor = scheduledFor
        )
    }
}
