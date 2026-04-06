package pl.detailing.crm.communication.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.shared.CommunicationChannel
import pl.detailing.crm.shared.CommunicationMessageType
import pl.detailing.crm.shared.CommunicationStatus
import java.time.Instant
import java.util.UUID

/**
 * Immutable audit record of every outbound communication (email or SMS) dispatched to a customer.
 *
 * Multi-tenant isolation is enforced via [studioId].
 * [visitId] is nullable — automation SMS fired before the visit exists, or standalone
 * communication not tied to any specific visit, will have visitId = null.
 * [customerId] is always set, enabling the full customer communication timeline.
 */
@Entity
@Table(
    name = "communication_log",
    indexes = [
        Index(name = "idx_comm_log_studio_id", columnList = "studio_id"),
        Index(name = "idx_comm_log_customer", columnList = "studio_id, customer_id"),
        Index(name = "idx_comm_log_visit", columnList = "visit_id"),
        Index(name = "idx_comm_log_sent_at", columnList = "studio_id, sent_at")
    ]
)
class CommunicationLogEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    /** Always set – the customer this message was sent to. */
    @Column(name = "customer_id", nullable = false, columnDefinition = "uuid")
    val customerId: UUID,

    /**
     * Set when the message is related to a specific visit.
     * Null for automation SMS sent before the visit was created,
     * or for any communication not tied to a visit.
     */
    @Column(name = "visit_id", nullable = true, columnDefinition = "uuid")
    val visitId: UUID?,

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 10)
    val channel: CommunicationChannel,

    @Enumerated(EnumType.STRING)
    @Column(name = "message_type", nullable = false, length = 50)
    val messageType: CommunicationMessageType,

    /** Phone number (E.164) for SMS, or email address for EMAIL messages. */
    @Column(name = "recipient_address", nullable = false, length = 255)
    val recipientAddress: String,

    /** Subject line – populated only for EMAIL messages. */
    @Column(name = "subject", nullable = true, length = 500)
    val subject: String?,

    /** Full body text of the message as delivered to the provider. */
    @Column(name = "body_content", nullable = false, columnDefinition = "TEXT")
    val bodyContent: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    val status: CommunicationStatus,

    /** Provider error description, null when status = SENT. */
    @Column(name = "error_message", nullable = true, columnDefinition = "TEXT")
    val errorMessage: String?,

    @Column(name = "sent_at", nullable = false, columnDefinition = "timestamp with time zone")
    val sentAt: Instant
)
