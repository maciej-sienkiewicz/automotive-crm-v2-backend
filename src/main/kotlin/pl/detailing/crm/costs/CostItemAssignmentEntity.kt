package pl.detailing.crm.costs

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/**
 * Assigns a single KSeF invoice item to a cost category.
 * item_id + category_id is unique — an item belongs to at most one category.
 * studio_id is denormalized for fast filtering.
 */
@Entity
@Table(
    name = "cost_item_assignments",
    indexes = [
        Index(name = "idx_cost_item_assign_category_id", columnList = "category_id"),
        Index(name = "idx_cost_item_assign_item_id",     columnList = "ksef_item_id"),
        Index(name = "idx_cost_item_assign_studio_id",   columnList = "studio_id"),
        Index(name = "idx_cost_item_assign_invoice_id",  columnList = "invoice_id"),
    ],
    uniqueConstraints = [
        UniqueConstraint(name = "uq_cost_item_assignment", columnNames = ["ksef_item_id"])
    ]
)
class CostItemAssignmentEntity(

    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "category_id", nullable = false, columnDefinition = "uuid")
    val categoryId: UUID,

    /** ID from ksef_invoice_items */
    @Column(name = "ksef_item_id", nullable = false, columnDefinition = "uuid")
    val ksefItemId: UUID,

    /** Denormalized — mirrors ksef_invoice_items.invoice_id for bulk-by-invoice queries */
    @Column(name = "invoice_id", nullable = false, columnDefinition = "uuid")
    val invoiceId: UUID,

    /** Denormalized for multi-tenant isolation */
    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "assigned_at", nullable = false, columnDefinition = "timestamp with time zone")
    val assignedAt: Instant = Instant.now()
)
