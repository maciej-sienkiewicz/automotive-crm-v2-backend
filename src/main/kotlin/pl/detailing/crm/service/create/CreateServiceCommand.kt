package pl.detailing.crm.service.create

import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VatRate

data class CreateServiceCommand(
    val studioId: StudioId,
    val userId: UserId,
    val name: String,
    val basePriceNet: Money,
    // Exact gross as entered by the user; null = derive from net (legacy clients).
    val basePriceGross: Money? = null,
    val vatRate: VatRate,
    val requireManualPrice: Boolean,
    val userName: String? = null
)