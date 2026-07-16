package pl.detailing.crm.visitcard.upsell.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import pl.detailing.crm.appointment.domain.AdjustmentType
import java.time.Instant
import java.util.UUID

/**
 * Lifecycle of a single upsell suggestion:
 *
 *   SUGGESTED – an employee intentionally attached the suggestion to this visit;
 *               it is visible on the customer's Visit Card and can still be removed.
 *   REQUESTED – the customer picked the suggestion on the Visit Card; a pending
 *               service item was created on the visit and a consent SMS
 *               ("Odpisz TAK…") was dispatched.
 *   CONFIRMED – the customer replied "TAK"; the pending service item was approved
 *               and the suggestion became a regular service on the visit.
 */
enum class UpsellSuggestionStatus { SUGGESTED, REQUESTED, CONFIRMED }

/**
 * A suggested additional service ("upsell") attached to a single visit/reservation.
 *
 * Suggestions are always assigned per visit — there is no studio-wide auto-suggest.
 * Pricing (including a potential discount) is frozen at suggestion time, exactly like
 * visit service items, so the price shown to the customer never drifts after
 * catalog changes.
 */
@Entity
@Table(
    name = "visit_upsell_suggestions",
    indexes = [
        Index(name = "idx_upsell_suggestions_visit", columnList = "studio_id, visit_id"),
        Index(name = "idx_upsell_suggestions_status", columnList = "visit_id, status")
    ]
)
class VisitUpsellSuggestionEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "visit_id", nullable = false, columnDefinition = "uuid")
    val visitId: UUID,

    /** Catalog service this suggestion was created from. */
    @Column(name = "service_id", nullable = false, columnDefinition = "uuid")
    val serviceId: UUID,

    // ── Frozen pricing snapshot ──────────────────────────────────────────────

    @Column(name = "service_name", nullable = false, length = 200)
    val serviceName: String,

    @Column(name = "base_price_net", nullable = false)
    val basePriceNet: Long,

    @Column(name = "vat_rate", nullable = false)
    val vatRate: Int,

    @Enumerated(EnumType.STRING)
    @Column(name = "adjustment_type", nullable = false, length = 20)
    val adjustmentType: AdjustmentType,

    /** PERCENT: basis points (negative = discount); other types: grosz. */
    @Column(name = "adjustment_value", nullable = false)
    val adjustmentValue: Long,

    @Column(name = "final_price_net", nullable = false)
    val finalPriceNet: Long,

    @Column(name = "final_price_gross", nullable = false)
    val finalPriceGross: Long,

    @Column(name = "note", length = 500)
    val note: String?,

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: UpsellSuggestionStatus = UpsellSuggestionStatus.SUGGESTED,

    /** Pending VisitServiceItem created when the customer requested this suggestion. */
    @Column(name = "service_item_id", columnDefinition = "uuid")
    var serviceItemId: UUID? = null,

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "requested_at", columnDefinition = "timestamp with time zone")
    var requestedAt: Instant? = null,

    @Column(name = "confirmed_at", columnDefinition = "timestamp with time zone")
    var confirmedAt: Instant? = null
)
