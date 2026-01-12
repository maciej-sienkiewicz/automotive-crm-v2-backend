package pl.detailing.crm.service.create

data class CreateServiceRequest(
    val name: String,
    val basePriceNet: Long,
    val vatRate: Int
)