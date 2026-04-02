package pl.detailing.crm.smscampaigns.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.smscampaigns.domain.SmsTriggerType
import java.time.Instant
import java.util.UUID

enum class SmsLogStatus { SENT, FAILED }

/**
 * Audit record for every SMS dispatch attempt.
 * The unique constraint on (appointmentId, triggerType) enforces deduplication:
 * once an SMS has been sent for a given appointment+trigger, it is never sent again.
 */
@Entity
@Table(
    name = "sms_send_log",
    indexes = [
        Index(name = "idx_sms_send_log_studio_id", columnList = "studio_id"),
        Index(name = "idx_sms_send_log_appointment_trigger", columnList = "appointment_id, trigger_type", unique = true)
    ]
)
class SmsLogEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "appointment_id", nullable = false, columnDefinition = "uuid")
    val appointmentId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 20)
    val triggerType: SmsTriggerType,

    @Column(name = "phone_number", nullable = false, length = 20)
    val phoneNumber: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    val status: SmsLogStatus,

    @Column(name = "external_message_id", length = 255)
    val externalMessageId: String?,

    @Column(name = "error_message", columnDefinition = "TEXT")
    val errorMessage: String?,

    @Column(name = "sent_at", nullable = false, columnDefinition = "timestamp with time zone")
    val sentAt: Instant
)
