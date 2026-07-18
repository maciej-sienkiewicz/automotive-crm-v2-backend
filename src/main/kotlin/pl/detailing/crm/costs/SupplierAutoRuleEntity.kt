package pl.detailing.crm.costs

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "supplier_auto_rules",
    uniqueConstraints = [UniqueConstraint(columnNames = ["studio_id", "seller_nip"])],
    indexes = [Index(name = "idx_supplier_auto_rules_studio", columnList = "studio_id")]
)
class SupplierAutoRuleEntity(

    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(name = "studio_id", nullable = false)
    val studioId: UUID,

    /** NIP normalized to digits only (no dashes). Used as the match key against ksef_invoices.seller_nip. */
    @Column(name = "seller_nip", nullable = false, length = 20)
    var sellerNip: String,

    /** Display name for the supplier (user-editable, pre-filled from invoice data). */
    @Column(name = "seller_name", nullable = false, length = 500)
    var sellerName: String,

    @Column(name = "category_id", nullable = false)
    var categoryId: UUID,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)
