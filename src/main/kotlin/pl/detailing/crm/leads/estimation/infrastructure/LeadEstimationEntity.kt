package pl.detailing.crm.leads.estimation.infrastructure

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "lead_estimations",
    indexes = [
        Index(name = "idx_lead_estimations_lead_id", columnList = "lead_id", unique = true),
        Index(name = "idx_lead_estimations_studio_id", columnList = "studio_id")
    ]
)
class LeadEstimationEntity(
    @Id
    val id: UUID,

    @Column(name = "lead_id", nullable = false, unique = true)
    val leadId: UUID,

    @Column(name = "studio_id", nullable = false)
    val studioId: UUID,

    @Convert(converter = StringListConverter::class)
    @Column(name = "extracted_needs", nullable = false, columnDefinition = "TEXT")
    var extractedNeeds: List<String> = emptyList(),

    @Convert(converter = StringListConverter::class)
    @Column(name = "unmatched_needs", nullable = false, columnDefinition = "TEXT")
    var unmatchedNeeds: List<String> = emptyList(),

    @Column(name = "total_gross", nullable = false)
    var totalGross: Long = 0L,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: LeadEstimationStatusJpa = LeadEstimationStatusJpa.PENDING,

    @OneToMany(
        mappedBy = "estimation",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        fetch = FetchType.EAGER
    )
    var items: MutableList<LeadEstimationItemEntity> = mutableListOf(),

    @Convert(converter = RelatedVisitListConverter::class)
    @Column(name = "related_visits", nullable = false, columnDefinition = "TEXT")
    var relatedVisits: List<RelatedVisit> = emptyList(),

    @Column(name = "ai_summary", nullable = true, columnDefinition = "TEXT")
    var aiSummary: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant
)

enum class LeadEstimationStatusJpa { PENDING, COMPLETED, FAILED }

@Entity
@Table(
    name = "lead_estimation_items",
    indexes = [
        Index(name = "idx_lead_est_items_estimation_id", columnList = "estimation_id")
    ]
)
class LeadEstimationItemEntity(
    @Id
    val id: UUID,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "estimation_id", nullable = false)
    var estimation: LeadEstimationEntity,

    @Column(name = "service_id")
    val serviceId: UUID?,

    @Column(name = "service_name", nullable = false, length = 500)
    val serviceName: String,

    @Column(name = "price_net", nullable = false)
    val priceNet: Long,

    @Column(name = "vat_rate", nullable = false)
    val vatRate: Int,

    @Column(name = "price_gross", nullable = false)
    val priceGross: Long,

    // Service has requireManualPrice — the price fields are zeroed and a human must quote it
    @Column(name = "manual_price_required", nullable = false, columnDefinition = "boolean not null default false")
    val manualPriceRequired: Boolean = false
)
