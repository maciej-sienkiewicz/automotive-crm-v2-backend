package pl.detailing.crm.batchorder.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(name = "batch_order_close_history")
class BatchOrderCloseHistoryEntity(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "studio_id", nullable = false)
    val studioId: UUID,

    @Column(name = "contractor_id", nullable = false)
    val contractorId: UUID,

    @Column(name = "closed_by_user_id", nullable = false)
    val closedByUserId: UUID,

    @Column(name = "closed_by_user_name")
    val closedByUserName: String?,

    @Column(name = "closed_at", nullable = false)
    val closedAt: Instant = Instant.now(),

    @Column(name = "period_from")
    val periodFrom: LocalDate?,

    @Column(name = "period_to")
    val periodTo: LocalDate?,

    @Column(name = "entry_count", nullable = false)
    val entryCount: Int,

    @Column(name = "total_net_cents", nullable = false)
    val totalNetCents: Long,

    @Column(name = "total_gross_cents", nullable = false)
    val totalGrossCents: Long,

    @Column(name = "mode", nullable = false)
    val mode: String,

    @Column(name = "finance_entry_created", nullable = false)
    val financeEntryCreated: Boolean = false,

    @Column(name = "email_requested", nullable = false)
    val emailRequested: Boolean = false,

    @Column(name = "email_sent", nullable = false)
    val emailSent: Boolean = false,

    @Column(name = "email_recipient")
    val emailRecipient: String? = null,

    @Column(name = "closed_entry_ids", nullable = false, columnDefinition = "TEXT")
    val closedEntryIds: String = "[]",

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now()
)
