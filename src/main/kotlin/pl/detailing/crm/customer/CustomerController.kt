package pl.detailing.crm.customer

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.customer.create.CreateCustomerCommand
import pl.detailing.crm.customer.create.CreateCustomerHandler
import pl.detailing.crm.customer.create.CreateCustomerRequest
import pl.detailing.crm.customer.domain.CompanyAddress
import pl.detailing.crm.customer.domain.CompanyData
import pl.detailing.crm.customer.domain.HomeAddress
import pl.detailing.crm.customer.get.GetCustomerByIdCommand
import pl.detailing.crm.customer.get.GetCustomerByIdHandler
import pl.detailing.crm.customer.list.CustomerListItem
import pl.detailing.crm.customer.list.ListCustomersHandler
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.UserRole
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/api/v1/customers")
class CustomerController(
    private val createCustomerHandler: CreateCustomerHandler,
    private val listCustomersHandler: ListCustomersHandler,
    private val getCustomerByIdHandler: GetCustomerByIdHandler
) {

    @GetMapping
    fun getCustomers(
        @RequestParam(required = false, defaultValue = "") search: String,
        @RequestParam(required = false, defaultValue = "1") page: Int,
        @RequestParam(required = false, defaultValue = "10") limit: Int,
        @RequestParam(required = false) sortBy: String?,
        @RequestParam(required = false, defaultValue = "asc") sortDirection: String
    ): ResponseEntity<CustomerListResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        var customers = listCustomersHandler.handle(principal.studioId)

        if (search.isNotBlank()) {
            customers = customers.filter {
                it.firstName.contains(search, ignoreCase = true) ||
                it.lastName.contains(search, ignoreCase = true) ||
                it.contact.email.contains(search, ignoreCase = true) ||
                it.contact.phone.contains(search, ignoreCase = true) ||
                (it.company?.name?.contains(search, ignoreCase = true) ?: false) ||
                (it.company?.nip?.contains(search, ignoreCase = true) ?: false)
            }
        }

        customers = when (sortBy) {
            "lastName" -> if (sortDirection == "asc") {
                customers.sortedBy { it.lastName }
            } else {
                customers.sortedByDescending { it.lastName }
            }
            "lastVisitDate" -> if (sortDirection == "asc") {
                customers.sortedBy { it.lastVisitDate }
            } else {
                customers.sortedByDescending { it.lastVisitDate }
            }
            "totalVisits" -> if (sortDirection == "asc") {
                customers.sortedBy { it.totalVisits }
            } else {
                customers.sortedByDescending { it.totalVisits }
            }
            "totalRevenue" -> if (sortDirection == "asc") {
                customers.sortedBy { it.totalRevenue.grossAmount }
            } else {
                customers.sortedByDescending { it.totalRevenue.grossAmount }
            }
            "vehicleCount" -> if (sortDirection == "asc") {
                customers.sortedBy { it.vehicleCount }
            } else {
                customers.sortedByDescending { it.vehicleCount }
            }
            "createdAt" -> if (sortDirection == "asc") {
                customers.sortedBy { it.createdAt }
            } else {
                customers.sortedByDescending { it.createdAt }
            }
            else -> customers.sortedBy { it.lastName }
        }

        val totalItems = customers.size
        val start = (page - 1) * limit
        val end = minOf(start + limit, totalItems)
        val paginatedCustomers = if (start < totalItems) {
            customers.subList(start, end)
        } else {
            emptyList()
        }

        ResponseEntity.ok(CustomerListResponse(
            data = paginatedCustomers,
            pagination = PaginationMeta(
                currentPage = page,
                totalPages = (totalItems + limit - 1) / limit,
                totalItems = totalItems,
                itemsPerPage = limit
            )
        ))
    }

    @GetMapping("/{customerId}")
    fun getCustomerById(@PathVariable customerId: String): ResponseEntity<CustomerDetailResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = GetCustomerByIdCommand(
            customerId = CustomerId(UUID.fromString(customerId)),
            studioId = principal.studioId
        )

        val result = getCustomerByIdHandler.handle(command)

        ResponseEntity.ok(CustomerDetailResponse(
            id = result.id,
            firstName = result.firstName,
            lastName = result.lastName,
            contact = CustomerContactResponse(
                email = result.contact.email,
                phone = result.contact.phone
            ),
            homeAddress = result.homeAddress?.let {
                HomeAddressResponse(
                    street = it.street,
                    city = it.city,
                    postalCode = it.postalCode,
                    country = it.country
                )
            },
            company = result.company?.let {
                CompanyDetailsResponse(
                    id = it.id,
                    name = it.name,
                    nip = it.nip,
                    regon = it.regon,
                    address = it.address?.let { addr ->
                        CompanyAddressResponse(
                            street = addr.street,
                            city = addr.city,
                            postalCode = addr.postalCode,
                            country = addr.country
                        )
                    }
                )
            },
            notes = result.notes,
            lastVisitDate = result.lastVisitDate?.toString(),
            totalVisits = result.totalVisits,
            vehicleCount = result.vehicleCount,
            totalRevenue = CustomerRevenueResponse(
                netAmount = result.totalRevenue.netAmount.toDouble(),
                grossAmount = result.totalRevenue.grossAmount.toDouble(),
                currency = result.totalRevenue.currency
            ),
            createdAt = result.createdAt.toString(),
            updatedAt = result.updatedAt.toString()
        ))
    }

    @PostMapping
    fun createCustomer(@RequestBody request: CreateCustomerRequest): ResponseEntity<CustomerResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can create customers")
        }

        val command = CreateCustomerCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            firstName = request.firstName,
            lastName = request.lastName,
            email = request.email,
            phone = request.phone,
            homeAddress = request.homeAddress?.let {
                HomeAddress(
                    street = it.street,
                    city = it.city,
                    postalCode = it.postalCode,
                    country = it.country
                )
            },
            companyData = request.companyData?.let {
                CompanyData(
                    name = it.name,
                    nip = it.nip,
                    regon = it.regon,
                    address = it.address?.let { addr ->
                        CompanyAddress(
                            street = addr.street,
                            city = addr.city,
                            postalCode = addr.postalCode,
                            country = addr.country
                        )
                    }
                )
            },
            notes = request.notes
        )

        val result = createCustomerHandler.handle(command)

        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(CustomerResponse(
                id = result.customerId.toString(),
                firstName = result.firstName,
                lastName = result.lastName,
                email = result.email,
                phone = result.phone,
                homeAddress = request.homeAddress?.let {
                    HomeAddressResponse(
                        street = it.street,
                        city = it.city,
                        postalCode = it.postalCode,
                        country = it.country
                    )
                },
                companyData = request.companyData?.let {
                    CompanyDataResponse(
                        name = it.name,
                        nip = it.nip,
                        regon = it.regon,
                        address = it.address?.let { addr ->
                            CompanyAddressResponse(
                                street = addr.street,
                                city = addr.city,
                                postalCode = addr.postalCode,
                                country = addr.country
                            )
                        }
                    )
                },
                notes = request.notes,
                isActive = true,
                createdAt = Instant.now().toString(),
                updatedAt = Instant.now().toString()
            ))
    }
}

data class CustomerResponse(
    val id: String,
    val firstName: String,
    val lastName: String,
    val email: String,
    val phone: String,
    val homeAddress: HomeAddressResponse?,
    val companyData: CompanyDataResponse?,
    val notes: String?,
    val isActive: Boolean,
    val createdAt: String,
    val updatedAt: String
)

data class HomeAddressResponse(
    val street: String,
    val city: String,
    val postalCode: String,
    val country: String
)

data class CompanyDataResponse(
    val name: String,
    val nip: String?,
    val regon: String?,
    val address: CompanyAddressResponse?
)

data class CompanyAddressResponse(
    val street: String,
    val city: String,
    val postalCode: String,
    val country: String
)

data class CustomerListResponse(
    val data: List<CustomerListItem>,
    val pagination: PaginationMeta
)

data class PaginationMeta(
    val currentPage: Int,
    val totalPages: Int,
    val totalItems: Int,
    val itemsPerPage: Int
)

data class CustomerDetailResponse(
    val id: String,
    val firstName: String,
    val lastName: String,
    val contact: CustomerContactResponse,
    val homeAddress: HomeAddressResponse?,
    val company: CompanyDetailsResponse?,
    val notes: String,
    val lastVisitDate: String?,
    val totalVisits: Int,
    val vehicleCount: Int,
    val totalRevenue: CustomerRevenueResponse,
    val createdAt: String,
    val updatedAt: String
)

data class CustomerContactResponse(
    val email: String,
    val phone: String
)

data class CompanyDetailsResponse(
    val id: String,
    val name: String,
    val nip: String?,
    val regon: String?,
    val address: CompanyAddressResponse?
)

data class CustomerRevenueResponse(
    val netAmount: Double,
    val grossAmount: Double,
    val currency: String
)
