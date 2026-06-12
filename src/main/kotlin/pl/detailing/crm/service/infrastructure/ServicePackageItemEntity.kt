package pl.detailing.crm.service.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.service.domain.ServicePackageItem
import pl.detailing.crm.service.domain.ServicePackageItemId
import pl.detailing.crm.shared.ServiceId
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "service_package_items",
    uniqueConstraints = [UniqueConstraint(name = "uq_package_service", columnNames = ["package_id", "service_id"])],
    indexes = [
        Index(name = "idx_spi_package_id", columnList = "package_id"),
        Index(name = "idx_spi_service_id", columnList = "service_id"),
        Index(name = "idx_spi_studio_id", columnList = "studio_id")
    ]
)
class ServicePackageItemEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "package_id", nullable = false, columnDefinition = "uuid")
    val packageId: UUID,

    @Column(name = "service_id", nullable = false, columnDefinition = "uuid")
    val serviceId: UUID,

    @Column(name = "service_name", nullable = false, length = 200)
    var serviceName: String,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "position", nullable = false)
    val position: Int = 0,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now()
) {
    fun toDomain() = ServicePackageItem(
        id = ServicePackageItemId(id),
        packageId = ServiceId(packageId),
        serviceId = ServiceId(serviceId),
        serviceName = serviceName,
        studioId = StudioId(studioId),
        position = position,
        createdAt = createdAt
    )

    companion object {
        fun fromDomain(item: ServicePackageItem) = ServicePackageItemEntity(
            id = item.id.value,
            packageId = item.packageId.value,
            serviceId = item.serviceId.value,
            serviceName = item.serviceName,
            studioId = item.studioId.value,
            position = item.position,
            createdAt = item.createdAt
        )
    }
}
