package pl.detailing.crm.service.domain

import pl.detailing.crm.shared.*
import java.time.Instant

data class Service(
    val id: ServiceId,
    val studioId: StudioId,
    val name: String,
    val basePriceNet: Money,
    val vatRate: VatRate,
    val isActive: Boolean,
    val requireManualPrice: Boolean,
    val replacesServiceId: ServiceId?,
    val createdBy: UserId,
    val updatedBy: UserId,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    fun calculateVatAmount(): Money = vatRate.calculateVatAmount(basePriceNet)

    fun calculateGrossPrice(): Money = vatRate.calculateGrossAmount(basePriceNet)

    fun archive(): Service = copy(isActive = false, updatedAt = Instant.now())

    fun createSnapshot(): ServiceSnapshot = ServiceSnapshot(
        serviceId = id,
        name = name,
        priceNet = basePriceNet,
        vatRate = vatRate,
        priceGross = calculateGrossPrice(),
        snapshotAt = Instant.now()
    )
}

data class ServiceSnapshot(
    val serviceId: ServiceId,
    val name: String,
    val priceNet: Money,
    val vatRate: VatRate,
    val priceGross: Money,
    val snapshotAt: Instant
)