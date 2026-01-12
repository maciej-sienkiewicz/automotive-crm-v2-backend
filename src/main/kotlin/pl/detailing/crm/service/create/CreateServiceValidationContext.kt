package pl.detailing.crm.service.create

import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VatRate

data class CreateServiceValidationContext(
    val studioId: StudioId,
    val name: String,
    val basePriceNet: Money,
    val vatRate: VatRate,
    val serviceNameExists: Boolean
)