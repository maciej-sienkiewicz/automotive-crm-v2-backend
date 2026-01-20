package pl.detailing.crm.customer.get

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.VisitStatus
import pl.detailing.crm.vehicle.infrastructure.VehicleOwnerRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.math.BigDecimal

@Service
class GetCustomerByIdHandler(
    private val customerRepository: CustomerRepository,
    private val visitRepository: VisitRepository,
    private val vehicleOwnerRepository: VehicleOwnerRepository
) {
    suspend fun handle(command: GetCustomerByIdCommand): GetCustomerByIdResult =
        withContext(Dispatchers.IO) {
            val entity = customerRepository.findByIdAndStudioId(
                id = command.customerId.value,
                studioId = command.studioId.value
            ) ?: throw NotFoundException("Customer not found")

            // Calculate visit statistics
            val visits = visitRepository.findByCustomerIdAndStudioId(
                customerId = command.customerId.value,
                studioId = command.studioId.value
            )

            val completedVisits = visits.filter { it.status == VisitStatus.COMPLETED }
            val totalVisits = completedVisits.size
            val lastVisitDate = completedVisits.maxByOrNull { it.scheduledDate }?.scheduledDate

            // Calculate revenue from completed visits only
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

            // Calculate vehicle count
            val vehicleOwners = vehicleOwnerRepository.findByCustomerId(command.customerId.value)
            val vehicleCount = vehicleOwners.size

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
                lastVisitDate = lastVisitDate,
                totalVisits = totalVisits,
                vehicleCount = vehicleCount,
                totalRevenue = revenueInfo,
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt
            )
        }
}
