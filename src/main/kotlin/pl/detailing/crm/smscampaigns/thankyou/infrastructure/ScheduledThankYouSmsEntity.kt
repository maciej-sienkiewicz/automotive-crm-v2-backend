package pl.detailing.crm.smscampaigns.thankyou.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.smscampaigns.thankyou.domain.ScheduledThankYouSms
import pl.detailing.crm.smscampaigns.thankyou.domain.ScheduledThankYouSmsStatus
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "scheduled_thank_you_sms",
    indexes = [
        Index(name = "idx_thank_you_sms_appointment", columnList = "appointment_id", unique = true),
        Index(name = "idx_thank_you_sms_visit", columnList = "visit_id"),
        Index(name = "idx_thank_you_sms_status_scheduled", columnList = "status, scheduled_for")
    ]
)
class ScheduledThankYouSmsEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "visit_id", nullable = false, columnDefinition = "uuid")
    val visitId: UUID,

    @Column(name = "appointment_id", nullable = false, columnDefinition = "uuid")
    val appointmentId: UUID,

    @Column(name = "customer_id", nullable = false, columnDefinition = "uuid")
    val customerId: UUID,

    @Column(name = "phone_number", length = 20)
    var phoneNumber: String?,

    @Column(name = "message_content", columnDefinition = "TEXT")
    var messageContent: String?,

    @Column(name = "scheduled_for", columnDefinition = "timestamp with time zone")
    var scheduledFor: Instant?,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: ScheduledThankYouSmsStatus,

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
    fun toDomain() = ScheduledThankYouSms(
        id = id,
        studioId = studioId,
        visitId = visitId,
        appointmentId = appointmentId,
        customerId = customerId,
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
        fun fromDomain(d: ScheduledThankYouSms) = ScheduledThankYouSmsEntity(
            id = d.id,
            studioId = d.studioId,
            visitId = d.visitId,
            appointmentId = d.appointmentId,
            customerId = d.customerId,
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
