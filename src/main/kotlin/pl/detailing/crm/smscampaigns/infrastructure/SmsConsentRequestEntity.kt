package pl.detailing.crm.smscampaigns.infrastructure

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class SmsConsentRequestStatus { PENDING, CONFIRMED, SUPERSEDED }

/**
 * Tracks 2-way SMS consent requests sent to customers when service scope changes.
 *
 * When a mechanic marks [notifyCustomer = true] on a services-change payload, an SMS
 * is dispatched asking the customer to reply "TAK".  This entity records the outgoing
 * message so that the inbound-reply webhook can correlate the response to the correct
 * visit and approve all pending service items automatically.
 *
 * Lifecycle:
 *   PENDING    – SMS sent, waiting for customer reply
 *   CONFIRMED  – Customer replied "TAK"; all pending items were approved
 *   SUPERSEDED – A newer consent request was created for the same visit before this one
 *                was answered (e.g. scope changed again)
 */
@Entity
@Table(
    name = "sms_consent_requests",
    indexes = [
        Index(name = "idx_sms_consent_studio_id", columnList = "studio_id"),
        Index(name = "idx_sms_consent_visit_id", columnList = "visit_id"),
        Index(name = "idx_sms_consent_phone_status", columnList = "customer_phone, status")
    ]
)
class SmsConsentRequestEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "visit_id", nullable = false, columnDefinition = "uuid")
    val visitId: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    /** E.164 format, e.g. +48100200300 */
    @Column(name = "customer_phone", nullable = false, length = 30)
    val customerPhone: String,

    /** Proposed total gross price (in grosz/cents) if all pending changes are approved. */
    @Column(name = "total_price_gross", nullable = false)
    val totalPriceGross: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: SmsConsentRequestStatus,

    /** ID returned by SMSAPI for the outgoing message. */
    @Column(name = "external_message_id", length = 255)
    val externalMessageId: String?,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant,

    @Column(name = "responded_at", columnDefinition = "timestamp with time zone")
    var respondedAt: Instant?
)
