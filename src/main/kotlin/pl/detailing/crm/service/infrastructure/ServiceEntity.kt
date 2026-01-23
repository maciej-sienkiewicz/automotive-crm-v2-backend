package pl.detailing.crm.service.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.service.domain.Service
import pl.detailing.crm.shared.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "services",
    indexes = [
        Index(name = "idx_services_studio_active", columnList = "studio_id, is_active"),
        Index(name = "idx_services_studio_name", columnList = "studio_id, name"),
        Index(name = "idx_services_replaces", columnList = "replaces_service_id"),
        Index(name = "idx_services_created_by", columnList = "created_by"),
        Index(name = "idx_services_updated_by", columnList = "updated_by")
    ]
)
class ServiceEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "name", nullable = false, length = 200)
    var name: String,

    @Column(name = "base_price_net", nullable = false)
    var basePriceNet: Long,

    @Column(name = "vat_rate", nullable = false)
    var vatRate: Int,

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,

    @Column(name = "require_manual_price", nullable = false)
    var requireManualPrice: Boolean = false,

    @Column(name = "replaces_service_id", columnDefinition = "uuid")
    var replacesServiceId: UUID?,

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "updated_by", nullable = false, columnDefinition = "uuid")
    var updatedBy: UUID,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant = Instant.now()
) {
    fun toDomain(): Service = Service(
        id = ServiceId(id),
        studioId = StudioId(studioId),
        name = name,
        basePriceNet = Money.fromCents(basePriceNet),
        vatRate = VatRate.fromInt(vatRate),
        isActive = isActive,
        requireManualPrice = requireManualPrice,
        replacesServiceId = replacesServiceId?.let { ServiceId(it) },
        createdBy = UserId(createdBy),
        updatedBy = UserId(updatedBy),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(service: Service): ServiceEntity = ServiceEntity(
            id = service.id.value,
            studioId = service.studioId.value,
            name = service.name,
            basePriceNet = service.basePriceNet.amountInCents,
            vatRate = service.vatRate.rate,
            isActive = service.isActive,
            requireManualPrice = service.requireManualPrice,
            replacesServiceId = service.replacesServiceId?.value,
            createdBy = service.createdBy.value,
            updatedBy = service.updatedBy.value,
            createdAt = service.createdAt,
            updatedAt = service.updatedAt
        )
    }
}