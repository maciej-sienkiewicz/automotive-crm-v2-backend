package pl.detailing.crm.batchorder.infrastructure

import jakarta.persistence.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(
    name = "batch_order_close_history",
    indexes = [Index(name = "idx_batch_close_history_studio_contractor", columnList = "studio_id, contractor_id")]
)
class BatchOrderCloseHistoryEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "contractor_id", nullable = false, columnDefinition = "uuid")
    val contractorId: UUID,

    @Column(name = "from_date", nullable = false)
    val fromDate: LocalDate,

    @Column(name = "to_date", nullable = false)
    val toDate: LocalDate,

    @Column(name = "mode", nullable = false, length = 20)
    val mode: String,

    @Column(name = "entry_count", nullable = false)
    val entryCount: Int,

    @Column(name = "total_net_cents", nullable = false)
    val totalNetCents: Long,

    @Column(name = "total_gross_cents", nullable = false)
    val totalGrossCents: Long,

    @Column(name = "email_sent", nullable = false)
    val emailSent: Boolean = false,

    @Column(name = "email_to", length = 255)
    val emailTo: String? = null,

    @Column(name = "closed_at", nullable = false, columnDefinition = "timestamp with time zone")
    val closedAt: Instant = Instant.now(),

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now()
)
