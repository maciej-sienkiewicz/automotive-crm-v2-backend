package pl.detailing.crm.smscampaigns.reminder.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.smscampaigns.reminder.domain.ScheduledSmsReminder
import pl.detailing.crm.smscampaigns.reminder.domain.ScheduledSmsReminderStatus
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "scheduled_sms_reminders",
    indexes = [
        Index(name = "idx_sms_reminders_studio_id", columnList = "studio_id"),
        Index(name = "idx_sms_reminders_visit_id", columnList = "visit_id"),
        Index(name = "idx_sms_reminders_status_scheduled", columnList = "status, scheduled_for"),
    ]
)
class ScheduledSmsReminderEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "visit_id", nullable = false, columnDefinition = "uuid")
    val visitId: UUID,

    @Column(name = "customer_id", nullable = false, columnDefinition = "uuid")
    val customerId: UUID,

    @Column(name = "appointment_id", nullable = false, columnDefinition = "uuid")
    val appointmentId: UUID,

    @Column(name = "phone_number", nullable = false, length = 20)
    val phoneNumber: String,

    @Column(name = "message_content", nullable = false, columnDefinition = "TEXT")
    var messageContent: String,

    @Column(name = "scheduled_for", nullable = false, columnDefinition = "timestamp with time zone")
    var scheduledFor: Instant,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: ScheduledSmsReminderStatus,

    @Column(name = "sent_at", columnDefinition = "timestamp with time zone")
    var sentAt: Instant? = null,

    @Column(name = "external_message_id", length = 255)
    var externalMessageId: String? = null,

    @Column(name = "error_message", columnDefinition = "TEXT")
    var errorMessage: String? = null,

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
) {
    fun toDomain() = ScheduledSmsReminder(
        id = id,
        studioId = studioId,
        visitId = visitId,
        customerId = customerId,
        appointmentId = appointmentId,
        phoneNumber = phoneNumber,
        messageContent = messageContent,
        scheduledFor = scheduledFor,
        status = status,
        sentAt = sentAt,
        externalMessageId = externalMessageId,
        errorMessage = errorMessage,
        createdBy = createdBy,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(d: ScheduledSmsReminder) = ScheduledSmsReminderEntity(
            id = d.id,
            studioId = d.studioId,
            visitId = d.visitId,
            customerId = d.customerId,
            appointmentId = d.appointmentId,
            phoneNumber = d.phoneNumber,
            messageContent = d.messageContent,
            scheduledFor = d.scheduledFor,
            status = d.status,
            sentAt = d.sentAt,
            externalMessageId = d.externalMessageId,
            errorMessage = d.errorMessage,
            createdBy = d.createdBy,
            createdAt = d.createdAt,
            updatedAt = d.updatedAt
        )
    }
}
