package pl.detailing.crm.worktime.infrastructure

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

enum class PeriodStatus { DRAFT, SUBMITTED, APPROVED, RETURNED }

@Entity
@Table(
    name = "work_time_periods",
    indexes = [
        Index(name = "idx_work_time_periods_user_id", columnList = "user_id"),
        Index(name = "idx_work_time_periods_studio", columnList = "studio_id"),
        Index(name = "idx_work_time_periods_user_per", columnList = "user_id, period")
    ]
)
class WorkTimePeriodEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    val userId: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    /** YYYY-MM */
    @Column(name = "period", nullable = false, length = 7)
    val period: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: PeriodStatus = PeriodStatus.DRAFT,

    @Column(name = "submitted_at", columnDefinition = "timestamp with time zone")
    var submittedAt: Instant? = null,

    @Column(name = "approved_at", columnDefinition = "timestamp with time zone")
    var approvedAt: Instant? = null,

    @Column(name = "approved_by", columnDefinition = "uuid")
    var approvedBy: UUID? = null,

    @Column(name = "returned_at", columnDefinition = "timestamp with time zone")
    var returnedAt: Instant? = null,

    @Column(name = "returned_by", columnDefinition = "uuid")
    var returnedBy: UUID? = null,

    @Column(name = "return_note", length = 1000)
    var returnNote: String? = null,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
)
