package pl.detailing.crm.leads.userquote.infrastructure

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "lead_user_quotes",
    indexes = [
        Index(name = "idx_lead_user_quotes_lead_id", columnList = "lead_id", unique = true),
        Index(name = "idx_lead_user_quotes_studio_id", columnList = "studio_id")
    ]
)
class LeadUserQuoteEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "lead_id", nullable = false, unique = true, columnDefinition = "uuid")
    val leadId: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @OneToMany(
        mappedBy = "quote",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        fetch = FetchType.EAGER
    )
    var items: MutableList<LeadUserQuoteItemEntity> = mutableListOf(),

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
)

@Entity
@Table(
    name = "lead_user_quote_items",
    indexes = [
        Index(name = "idx_lead_user_quote_items_quote_id", columnList = "quote_id")
    ]
)
class LeadUserQuoteItemEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "quote_id", nullable = false)
    var quote: LeadUserQuoteEntity,

    @Column(name = "service_name", nullable = false, length = 500)
    var serviceName: String,

    @Column(name = "price_net", nullable = false)
    var priceNet: Long,

    @Column(name = "vat_rate", nullable = false)
    var vatRate: Int,

    @Column(name = "price_gross", nullable = false)
    var priceGross: Long
)
