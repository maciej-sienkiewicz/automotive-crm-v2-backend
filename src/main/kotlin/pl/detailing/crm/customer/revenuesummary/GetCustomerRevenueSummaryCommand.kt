package pl.detailing.crm.customer.revenuesummary

import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.StudioId

data class GetCustomerRevenueSummaryCommand(
    val customerId: CustomerId,
    val studioId: StudioId,
    val months: Int = 12
)
