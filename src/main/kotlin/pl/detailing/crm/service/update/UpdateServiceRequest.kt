package pl.detailing.crm.service.update

data class UpdateServiceRequest(
    val originalServiceId: String,
    val name: String,
    val basePriceNet: Long,
    val vatRate: Int,
    val requireManualPrice: Boolean = false
)
