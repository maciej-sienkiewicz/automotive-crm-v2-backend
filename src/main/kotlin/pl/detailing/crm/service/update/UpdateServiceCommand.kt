package pl.detailing.crm.service.update

import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.ServiceId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VatRate

data class UpdateServiceCommand(
    val studioId: StudioId,
    val userId: UserId,
    val oldServiceId: ServiceId,
    val name: String,
    val basePriceNet: Money,
    // Exact gross as entered by the user; null = derive from net (legacy clients).
    val basePriceGross: Money? = null,
    val vatRate: VatRate,
    val requireManualPrice: Boolean,
    val userName: String? = null
)