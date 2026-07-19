package pl.detailing.crm.service.domain

import pl.detailing.crm.shared.*
import java.time.Instant

data class Service(
    val id: ServiceId,
    val studioId: StudioId,
    val name: String,
    val basePriceNet: Money,
    /**
     * Stored gross price — exactly as entered by the user. Not derivable from
     * [basePriceNet]: net→gross rounding skips some gross values entirely
     * (e.g. no integer net maps to 201.00 PLN at 23% VAT), so recomputing
     * gross from net would silently shift user-entered prices by 1 grosz.
     */
    val basePriceGross: Money,
    val vatRate: VatRate,
    val isActive: Boolean,
    val requireManualPrice: Boolean,
    val isPackage: Boolean,
    val replacesServiceId: ServiceId?,
    val createdBy: UserId,
    val updatedBy: UserId,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    fun calculateVatAmount(): Money = basePriceGross.minus(basePriceNet)

    fun calculateGrossPrice(): Money = basePriceGross

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