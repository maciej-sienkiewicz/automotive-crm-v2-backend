package pl.detailing.crm.subscription.management

import jakarta.persistence.*
import pl.detailing.crm.subscription.entitlement.domain.PlanKey
import java.time.Instant
import java.util.UUID

enum class PendingPlanChangeStatus { PENDING, APPLIED, CANCELLED }

/**
 * Records a deferred plan downgrade that will be applied by [PlanDowngradeScheduler]
 * at [effectiveAt] (= the end of the studio's current billing period).
 *
 * Invariant: at most one PENDING row per studio (enforced by unique constraint).
 * When the user upgrades before [effectiveAt], the row is moved to CANCELLED.
 * When the scheduler processes the row, it is moved to APPLIED.
 *
 * [fromPlanKey] is stored for audit/history purposes — it is the plan active
 * when the downgrade was requested, not necessarily at the time it is applied.
 */
@Entity
@Table(
    name = "pending_plan_changes",
    indexes = [
        Index(name = "idx_pending_plan_changes_studio_status", columnList = "studio_id, status"),
        Index(name = "idx_pending_plan_changes_effective_at", columnList = "effective_at, status")
    ]
)
class PendingPlanChangeEntity(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "studio_id", nullable = false)
    val studioId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "from_plan_key", nullable = false, length = 50)
    val fromPlanKey: PlanKey,

    @Enumerated(EnumType.STRING)
    @Column(name = "to_plan_key", nullable = false, length = 50)
    val toPlanKey: PlanKey,

    @Column(name = "effective_at", nullable = false)
    val effectiveAt: Instant,

    @Column(name = "requested_at", nullable = false)
    val requestedAt: Instant = Instant.now(),

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: PendingPlanChangeStatus = PendingPlanChangeStatus.PENDING,

    @Column(name = "applied_at")
    var appliedAt: Instant? = null
)
