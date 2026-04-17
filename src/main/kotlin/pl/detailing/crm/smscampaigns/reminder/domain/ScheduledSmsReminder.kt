package pl.detailing.crm.smscampaigns.reminder.domain

import java.time.Instant
import java.util.UUID

enum class ScheduledSmsReminderStatus {
    PENDING,
    SENT,
    FAILED,
    CANCELLED
}

/**
 * A single, per-visit SMS reminder manually scheduled by a studio employee.
 *
 * Lifecycle: PENDING → SENT (scheduler dispatches) or FAILED (SMS provider error)
 *            PENDING → CANCELLED (employee cancels before send)
 *
 * The [phoneNumber] is resolved at scheduling time so future customer data
 * changes don't affect the already-scheduled message.
 */
data class ScheduledSmsReminder(
    val id: UUID,
    val studioId: UUID,
    val visitId: UUID,
    val customerId: UUID,
    val appointmentId: UUID,
    val phoneNumber: String,
    val messageContent: String,
    val scheduledFor: Instant,
    val status: ScheduledSmsReminderStatus,
    val sentAt: Instant?,
    val externalMessageId: String?,
    val errorMessage: String?,
    val createdBy: UUID,
    val createdAt: Instant,
    val updatedAt: Instant
)
