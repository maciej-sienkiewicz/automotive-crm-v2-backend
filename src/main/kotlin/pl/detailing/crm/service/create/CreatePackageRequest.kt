package pl.detailing.crm.service.create

data class CreatePackageRequest(
    val name: String,
    val basePriceNet: Long,
    val vatRate: Int,
    val requireManualPrice: Boolean = false,
    val serviceIds: List<String>
)
