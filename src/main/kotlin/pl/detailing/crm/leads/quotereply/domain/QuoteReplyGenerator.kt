package pl.detailing.crm.leads.quotereply.domain

interface QuoteReplyGenerator {
    suspend fun generate(input: QuoteReplyInput): QuoteReplyResult
}

data class QuoteReplyInput(
    val customerName: String?,
    val initialMessage: String?,
    val vehicleBrand: String?,
    val vehicleModel: String?,
    val quoteItems: List<QuoteReplyItem>
)

data class QuoteReplyItem(
    val serviceName: String,
    val priceGross: Long,
    val vatRate: Int
)

data class QuoteReplyResult(
    val title: String,
    val reply: String
)
