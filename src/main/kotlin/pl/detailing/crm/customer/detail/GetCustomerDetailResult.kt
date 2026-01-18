package pl.detailing.crm.customer.detail

import pl.detailing.crm.customer.domain.CompanyAddress
import pl.detailing.crm.customer.domain.HomeAddress
import java.math.BigDecimal
import java.time.Instant

data class GetCustomerDetailResult(
    val customer: CustomerDetailInfo,
    val marketingConsents: List<MarketingConsentInfo>,
    val loyaltyTier: LoyaltyTier,
    val lifetimeValue: RevenueInfo
)

data class CustomerDetailInfo(
    val id: String,
    val firstName: String,
    val lastName: String,
    val contact: ContactInfo,
    val homeAddress: HomeAddress?,
    val company: CompanyDetails?,
    val notes: String,
    val lastVisitDate: String?,
    val totalVisits: Int,
    val vehicleCount: Int,
    val totalRevenue: RevenueInfo,
    val createdAt: String,
    val updatedAt: String
)

data class ContactInfo(
    val email: String,
    val phone: String
)

data class CompanyDetails(
    val id: String,
    val name: String,
    val nip: String?,
    val regon: String?,
    val address: CompanyAddress?
)

data class RevenueInfo(
    val netAmount: BigDecimal,
    val grossAmount: BigDecimal,
    val currency: String
)

data class MarketingConsentInfo(
    val id: String,
    val type: MarketingConsentType,
    val granted: Boolean,
    val grantedAt: String?,
    val revokedAt: String?,
    val lastModifiedBy: String
)

enum class MarketingConsentType {
    EMAIL,
    SMS,
    PHONE,
    POSTAL
}

enum class LoyaltyTier {
    BRONZE,
    SILVER,
    GOLD,
    PLATINUM
}
