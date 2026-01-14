package pl.detailing.crm.customer.get

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.NotFoundException
import java.math.BigDecimal

@Service
class GetCustomerByIdHandler(
    private val customerRepository: CustomerRepository
) {
    suspend fun handle(command: GetCustomerByIdCommand): GetCustomerByIdResult =
        withContext(Dispatchers.IO) {
            val entity = customerRepository.findByIdAndStudioId(
                id = command.customerId.value,
                studioId = command.studioId.value
            ) ?: throw NotFoundException("Customer not found")

            GetCustomerByIdResult(
                id = entity.id.toString(),
                firstName = entity.firstName,
                lastName = entity.lastName,
                contact = ContactInfo(
                    email = entity.email,
                    phone = entity.phone
                ),
                homeAddress = if (entity.homeAddressStreet != null &&
                    entity.homeAddressCity != null &&
                    entity.homeAddressPostalCode != null &&
                    entity.homeAddressCountry != null
                ) {
                    pl.detailing.crm.customer.domain.HomeAddress(
                        street = entity.homeAddressStreet!!,
                        city = entity.homeAddressCity!!,
                        postalCode = entity.homeAddressPostalCode!!,
                        country = entity.homeAddressCountry!!
                    )
                } else null,
                company = if (entity.companyName != null) {
                    CompanyDetails(
                        id = entity.id.toString(),
                        name = entity.companyName!!,
                        nip = entity.companyNip,
                        regon = entity.companyRegon,
                        address = if (entity.companyAddressStreet != null &&
                            entity.companyAddressCity != null &&
                            entity.companyAddressPostalCode != null &&
                            entity.companyAddressCountry != null
                        ) {
                            pl.detailing.crm.customer.domain.CompanyAddress(
                                street = entity.companyAddressStreet!!,
                                city = entity.companyAddressCity!!,
                                postalCode = entity.companyAddressPostalCode!!,
                                country = entity.companyAddressCountry!!
                            )
                        } else null
                    )
                } else null,
                notes = entity.notes ?: "",
                lastVisitDate = null, // TODO: Calculate from visits
                totalVisits = 0, // TODO: Calculate from visits
                vehicleCount = 0, // TODO: Calculate from vehicles
                totalRevenue = RevenueInfo(
                    netAmount = BigDecimal.ZERO,
                    grossAmount = BigDecimal.ZERO,
                    currency = "PLN"
                ), // TODO: Calculate from visits
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt
            )
        }
}
