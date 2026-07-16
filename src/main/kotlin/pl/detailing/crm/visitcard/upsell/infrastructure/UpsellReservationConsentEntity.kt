package pl.detailing.crm.visitcard.upsell.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

enum class UpsellReservationConsentStatus { PENDING, CONFIRMED, SUPERSEDED }

/**
 * Tracks the "Odpisz TAK…" consent SMS sent when a customer requests upsell
 * services on a reservation card, before check-in.
 *
 * Visits use the shared [pl.detailing.crm.smscampaigns.infrastructure.SmsConsentRequestEntity]
 * mechanism (approving pending visit service items); reservations have no pending
 * item concept, so this dedicated record correlates the inbound "TAK" with the
 * appointment whose suggestions should be added as line items.
 */
@Entity
@Table(
    name = "upsell_reservation_consents",
    indexes = [
        Index(name = "idx_upsell_res_consents_appointment", columnList = "appointment_id"),
        Index(name = "idx_upsell_res_consents_phone_status", columnList = "customer_phone, status")
    ]
)
class UpsellReservationConsentEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "appointment_id", nullable = false, columnDefinition = "uuid")
    val appointmentId: UUID,

    /** E.164 format, e.g. +48100200300 */
    @Column(name = "customer_phone", nullable = false, length = 30)
    val customerPhone: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: UpsellReservationConsentStatus,

    @Column(name = "external_message_id", length = 255)
    val externalMessageId: String?,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "responded_at", columnDefinition = "timestamp with time zone")
    var respondedAt: Instant? = null
)

@Repository
interface UpsellReservationConsentRepository : JpaRepository<UpsellReservationConsentEntity, UUID> {

    fun findTopByCustomerPhoneAndStatusOrderByCreatedAtDesc(
        customerPhone: String,
        status: UpsellReservationConsentStatus
    ): UpsellReservationConsentEntity?

    @Modifying
    @Query("""
        UPDATE UpsellReservationConsentEntity r
        SET r.status = 'SUPERSEDED'
        WHERE r.appointmentId = :appointmentId AND r.status = 'PENDING'
    """)
    fun supersedePendingByAppointmentId(@Param("appointmentId") appointmentId: UUID)
}
