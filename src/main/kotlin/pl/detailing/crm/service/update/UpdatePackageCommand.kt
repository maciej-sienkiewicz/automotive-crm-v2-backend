package pl.detailing.crm.service.update

import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.ServiceId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VatRate

data class UpdatePackageCommand(
    val studioId: StudioId,
    val userId: UserId,
    val originalPackageId: ServiceId,
    val name: String,
    val basePriceNet: Money,
    val vatRate: VatRate,
    val requireManualPrice: Boolean,
    val serviceIds: List<ServiceId>,
    val userName: String? = null
)
