package pl.detailing.crm.service.domain

import pl.detailing.crm.shared.ServiceId
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.util.UUID

@JvmInline
value class ServicePackageItemId(val value: UUID) {
    companion object {
        fun random() = ServicePackageItemId(UUID.randomUUID())
    }
}

data class ServicePackageItem(
    val id: ServicePackageItemId,
    val packageId: ServiceId,
    val serviceId: ServiceId,
    val serviceName: String,
    val studioId: StudioId,
    val position: Int,
    val createdAt: Instant
)
