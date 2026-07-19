package pl.detailing.crm.service.update

data class UpdatePackageRequest(
    val originalPackageId: String,
    val name: String,
    val basePriceNet: Long,
    val basePriceGross: Long? = null,
    val vatRate: Int,
    val requireManualPrice: Boolean,
    val serviceIds: List<String>
)
