package pl.detailing.crm.leads.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import pl.detailing.crm.leads.domain.LeadLostReason
import pl.detailing.crm.shared.LeadStatus
import java.time.Instant
import java.util.UUID

/**
 * One priced service assigned to a lead. Price is a frozen copy from the catalogue at
 * assignment time — later cennik edits must never change a quoted lead's value.
 */
@Entity
@Table(
    name = "lead_service_items",
    indexes = [
        Index(name = "idx_lead_service_items_lead", columnList = "lead_id")
    ]
)
class LeadServiceItemEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "lead_id", nullable = false, columnDefinition = "uuid")
    val leadId: UUID,

    /** Null for a custom, one-off position typed by hand. */
    @Column(name = "service_id", columnDefinition = "uuid")
    val serviceId: UUID?,

    @Column(name = "name", nullable = false, length = 200)
    val name: String,

    /** Cena brutto w groszach, zamrożona w momencie przypisania. */
    @Column(name = "price_gross", nullable = false)
    val priceGross: Long,

    @Column(name = "quantity", nullable = false)
    val quantity: Int,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)

/**
 * Append-only trail of status transitions — the raw material of the funnel,
 * conversion and loss analytics. Never updated, never deleted with the lead's edits.
 */
@Entity
@Table(
    name = "lead_status_history",
    indexes = [
        Index(name = "idx_lead_status_history_lead", columnList = "lead_id, created_at"),
        Index(name = "idx_lead_status_history_studio", columnList = "studio_id, created_at")
    ]
)
class LeadStatusHistoryEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "lead_id", nullable = false, columnDefinition = "uuid")
    val leadId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 20)
    val fromStatus: LeadStatus?,

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 20)
    val toStatus: LeadStatus,

    @Enumerated(EnumType.STRING)
    @Column(name = "lost_reason_code", length = 30)
    val lostReasonCode: LeadLostReason?,

    @Column(name = "changed_by_user_id", columnDefinition = "uuid")
    val changedByUserId: UUID?,

    @Column(name = "changed_by_name", length = 200)
    val changedByName: String?,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)
