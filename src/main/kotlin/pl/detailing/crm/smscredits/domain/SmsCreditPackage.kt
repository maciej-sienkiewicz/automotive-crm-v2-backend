package pl.detailing.crm.smscredits.domain

import java.math.BigDecimal
import java.util.UUID

data class SmsCreditPackage(
    val id: UUID,
    val name: String,
    val creditAmount: Int,
    val priceGrossInCents: Long,
    val currency: String,
    val isActive: Boolean
) {
    val priceGross: BigDecimal get() = BigDecimal(priceGrossInCents).movePointLeft(2)
    val pricePerCreditInCents: Long get() = priceGrossInCents / creditAmount
}
