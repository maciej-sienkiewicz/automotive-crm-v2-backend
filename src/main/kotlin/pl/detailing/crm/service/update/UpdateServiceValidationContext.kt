package pl.detailing.crm.service.update

import pl.detailing.crm.service.infrastructure.ServiceEntity
import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.ServiceId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VatRate

data class UpdateServiceValidationContext(
    val studioId: StudioId,
    val oldServiceId: ServiceId,
    val oldService: ServiceEntity?,
    val name: String,
    val basePriceNet: Money,
    val vatRate: VatRate,
    val nameConflictExists: Boolean
)