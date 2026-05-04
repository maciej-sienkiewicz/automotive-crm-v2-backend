package pl.detailing.crm.customer.revenuesummary

data class GetCustomerRevenueSummaryResult(
    val buckets: List<RevenueBucket>,
    val total: RevenueTotal,
    val period: RevenuePeriod
)

data class RevenueBucket(
    val year: Int,
    val month: Int,
    val grossAmount: Long,
    val currency: String,
    val visitCount: Int
)

data class RevenueTotal(
    val grossAmount: Long,
    val netAmount: Long,
    val currency: String,
    val visitCount: Int
)

data class RevenuePeriod(
    val from: String,
    val to: String
)
