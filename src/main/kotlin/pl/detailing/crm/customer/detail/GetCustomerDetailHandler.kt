package pl.detailing.crm.customer.detail

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.consent.infrastructure.ConsentDefinitionRepository
import pl.detailing.crm.customer.consent.infrastructure.ConsentTemplateRepository
import pl.detailing.crm.customer.consent.infrastructure.CustomerConsentRepository
import pl.detailing.crm.customer.domain.CompanyAddress
import pl.detailing.crm.customer.domain.HomeAddress
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.math.BigDecimal
import java.time.Instant

@Service
class GetCustomerDetailHandler(
    private val customerRepository: CustomerRepository,
    private val visitRepository: VisitRepository,
    private val vehicleOwnerRepository: VehicleOwnerRepository,
    private val consentDefinitionRepository: ConsentDefinitionRepository,
    private val consentTemplateRepository: ConsentTemplateRepository,
    private val customerConsentRepository: CustomerConsentRepository
) {
    suspend fun handle(command: GetCustomerDetailCommand): GetCustomerDetailResult =
        withContext(Dispatchers.IO) {
            // Step 1: Get customer basic data
            val customerEntity = customerRepository.findByIdAndStudioId(
                id = command.customerId.value,
                studioId = command.studioId.value
            ) ?: throw NotFoundException("Customer not found")

            // Step 2: Calculate visit statistics
            val visits = visitRepository.findByCustomerIdAndStudioId(
                customerId = command.customerId.value,
                studioId = command.studioId.value
            )

            val completedVisits = visits.filter { it.status == pl.detailing.crm.shared.VisitStatus.COMPLETED }
            val totalVisits = completedVisits.size
            val lastVisitDate = completedVisits.maxByOrNull { it.scheduledDate }?.scheduledDate

            // Step 3: Calculate revenue
            var totalNetAmount = 0L
            var totalGrossAmount = 0L

            completedVisits.forEach { visit ->
                visit.serviceItems.forEach { serviceItem ->
                    totalNetAmount += serviceItem.finalPriceNet
                    totalGrossAmount += serviceItem.finalPriceGross
                }
            }

            val revenueInfo = RevenueInfo(
                netAmount = BigDecimal.valueOf(totalNetAmount).divide(BigDecimal.valueOf(100)),
                grossAmount = BigDecimal.valueOf(totalGrossAmount).divide(BigDecimal.valueOf(100)),
                currency = "PLN"
            )

            // Step 4: Calculate vehicle count
            val vehicleOwners = vehicleOwnerRepository.findByCustomerId(command.customerId.value)
            val vehicleCount = vehicleOwners.size

            // Step 5: Get marketing consents
            val activeDefinitions = consentDefinitionRepository.findActiveByStudioId(
                studioId = command.studioId.value
            )

            val allTemplates = consentTemplateRepository.findAllActiveByStudioId(command.studioId.value)
            val customerConsents = customerConsentRepository.findAllByCustomerIdAndStudioId(
                customerId = command.customerId.value,
                studioId = command.studioId.value
            )

            val marketingConsents = activeDefinitions.mapNotNull { definition ->
                // Map definition slug to consent type
                val consentType = mapSlugToConsentType(definition.slug) ?: return@mapNotNull null

                // Find active template for this definition
                val activeTemplate = allTemplates.firstOrNull {
                    it.definitionId == definition.id && it.isActive
                }

                // Find all templates for this definition
                val definitionTemplates = allTemplates
                    .filter { it.definitionId == definition.id }
                    .map { it.id }

                // Find customer's consent for this definition (latest)
                val latestConsent = customerConsents
                    .filter { it.templateId in definitionTemplates }
                    .maxByOrNull { it.signedAt }

                MarketingConsentInfo(
                    id = definition.id.toString(),
                    type = consentType,
                    granted = latestConsent != null &&
                              activeTemplate != null &&
                              latestConsent.templateId == activeTemplate.id,
                    grantedAt = if (latestConsent != null) latestConsent.signedAt.toString() else null,
                    revokedAt = if (latestConsent != null && activeTemplate != null &&
                                    latestConsent.templateId != activeTemplate.id) {
                        // If there's a newer template they haven't signed, consider it revoked
                        latestConsent.signedAt.toString()
                    } else null,
                    lastModifiedBy = "System" // We don't track who modified it yet
                )
            }

            // Step 6: Calculate loyalty tier based on lifetime value
            val loyaltyTier = calculateLoyaltyTier(revenueInfo.grossAmount)

            // Step 7: Build customer info
            val customerInfo = CustomerDetailInfo(
                id = customerEntity.id.toString(),
                firstName = customerEntity.firstName,
                lastName = customerEntity.lastName,
                contact = ContactInfo(
                    email = customerEntity.email,
                    phone = customerEntity.phone
                ),
                homeAddress = if (customerEntity.homeAddressStreet != null &&
                    customerEntity.homeAddressCity != null &&
                    customerEntity.homeAddressPostalCode != null &&
                    customerEntity.homeAddressCountry != null
                ) {
                    HomeAddress(
                        street = customerEntity.homeAddressStreet!!,
                        city = customerEntity.homeAddressCity!!,
                        postalCode = customerEntity.homeAddressPostalCode!!,
                        country = customerEntity.homeAddressCountry!!
                    )
                } else null,
                company = if (customerEntity.companyName != null) {
                    CompanyDetails(
                        id = customerEntity.id.toString(),
                        name = customerEntity.companyName!!,
                        nip = customerEntity.companyNip,
                        regon = customerEntity.companyRegon,
                        address = if (customerEntity.companyAddressStreet != null &&
                            customerEntity.companyAddressCity != null &&
                            customerEntity.companyAddressPostalCode != null &&
                            customerEntity.companyAddressCountry != null
                        ) {
                            CompanyAddress(
                                street = customerEntity.companyAddressStreet!!,
                                city = customerEntity.companyAddressCity!!,
                                postalCode = customerEntity.companyAddressPostalCode!!,
                                country = customerEntity.companyAddressCountry!!
                            )
                        } else null
                    )
                } else null,
                notes = customerEntity.notes ?: "",
                lastVisitDate = lastVisitDate?.toString(),
                totalVisits = totalVisits,
                vehicleCount = vehicleCount,
                totalRevenue = revenueInfo,
                createdAt = customerEntity.createdAt.toString(),
                updatedAt = customerEntity.updatedAt.toString()
            )

            GetCustomerDetailResult(
                customer = customerInfo,
                marketingConsents = marketingConsents,
                loyaltyTier = loyaltyTier,
                lifetimeValue = revenueInfo
            )
        }

    private fun mapSlugToConsentType(slug: String): MarketingConsentType? {
        return when (slug.lowercase()) {
            "email", "marketing-email", "email-marketing" -> MarketingConsentType.EMAIL
            "sms", "marketing-sms", "sms-marketing" -> MarketingConsentType.SMS
            "phone", "marketing-phone", "phone-marketing" -> MarketingConsentType.PHONE
            "postal", "marketing-postal", "postal-marketing" -> MarketingConsentType.POSTAL
            else -> null // Skip non-marketing consents like RODO
        }
    }

    private fun calculateLoyaltyTier(lifetimeValue: BigDecimal): LoyaltyTier {
        return when {
            lifetimeValue >= BigDecimal.valueOf(50000) -> LoyaltyTier.PLATINUM
            lifetimeValue >= BigDecimal.valueOf(20000) -> LoyaltyTier.GOLD
            lifetimeValue >= BigDecimal.valueOf(5000) -> LoyaltyTier.SILVER
            else -> LoyaltyTier.BRONZE
        }
    }
}
