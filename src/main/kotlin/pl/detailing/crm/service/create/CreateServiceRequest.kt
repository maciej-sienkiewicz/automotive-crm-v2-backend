package pl.detailing.crm.service.create

data class CreateServiceRequest(
    val name: String,
    val basePriceNet: Long,
    // Exact gross as entered by the user; null (older clients) = derive from net.
    val basePriceGross: Long? = null,
    val vatRate: Int,
    val requireManualPrice: Boolean = false
)
