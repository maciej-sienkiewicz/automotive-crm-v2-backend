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
import pl.detailing.crm.customer.vehicles.GetCustomerVehiclesHandler
import pl.detailing.crm.customer.vehicles.VehicleResponse
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
    private val getCustomerByIdHandler: GetCustomerByIdHandler,
    private val getCustomerVehiclesHandler: GetCustomerVehiclesHandler,
    private val getCustomerDetailHandler: pl.detailing.crm.customer.detail.GetCustomerDetailHandler,
    private val updateCustomerHandler: pl.detailing.crm.customer.update.UpdateCustomerHandler,
    private val updateCompanyHandler: pl.detailing.crm.customer.update.UpdateCompanyHandler,
    private val deleteCompanyHandler: pl.detailing.crm.customer.update.DeleteCompanyHandler,
    private val updateNotesHandler: pl.detailing.crm.customer.update.UpdateNotesHandler,
    private val getCustomerVisitsHandler: pl.detailing.crm.customer.visits.GetCustomerVisitsHandler
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
                (it.firstName?.contains(search, ignoreCase = true) ?: false) ||
                (it.lastName?.contains(search, ignoreCase = true) ?: false) ||
                (it.contact.email?.contains(search, ignoreCase = true) ?: false) ||
                (it.contact.phone?.contains(search, ignoreCase = true) ?: false) ||
                (it.company?.name?.contains(search, ignoreCase = true) ?: false) ||
                (it.company?.nip?.contains(search, ignoreCase = true) ?: false)
            }
        }

        customers = when (sortBy) {
            "lastName" -> if (sortDirection == "asc") {
                customers.sortedBy { it.lastName ?: "" }
            } else {
                customers.sortedByDescending { it.lastName ?: "" }
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
            else -> customers.sortedBy { it.lastName ?: "" }
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
            lastVisitDate = result.lastVisitDate,
            totalVisits = result.totalVisits,
            vehicleCount = result.vehicleCount,
            totalRevenue = CustomerRevenueResponse(
                netAmount = result.totalRevenue.netAmount.toDouble(),
                grossAmount = result.totalRevenue.grossAmount.toDouble(),
                currency = result.totalRevenue.currency
            ),
            createdAt = result.createdAt,
            updatedAt = result.updatedAt
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
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            ))
    }

    @GetMapping("/{customerId}/detail")
    fun getCustomerDetail(@PathVariable customerId: String): ResponseEntity<CustomerDetailFullResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = pl.detailing.crm.customer.detail.GetCustomerDetailCommand(
            customerId = CustomerId(UUID.fromString(customerId)),
            studioId = principal.studioId
        )

        val result = getCustomerDetailHandler.handle(command)

        ResponseEntity.ok(CustomerDetailFullResponse(
            customer = CustomerDetailResponse(
                id = result.customer.id,
                firstName = result.customer.firstName,
                lastName = result.customer.lastName,
                contact = CustomerContactResponse(
                    email = result.customer.contact.email,
                    phone = result.customer.contact.phone
                ),
                homeAddress = result.customer.homeAddress?.let {
                    HomeAddressResponse(
                        street = it.street,
                        city = it.city,
                        postalCode = it.postalCode,
                        country = it.country
                    )
                },
                company = result.customer.company?.let {
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
                notes = result.customer.notes,
                lastVisitDate = result.customer.lastVisitDate,
                totalVisits = result.customer.totalVisits,
                vehicleCount = result.customer.vehicleCount,
                totalRevenue = CustomerRevenueResponse(
                    netAmount = result.customer.totalRevenue.netAmount.toDouble(),
                    grossAmount = result.customer.totalRevenue.grossAmount.toDouble(),
                    currency = result.customer.totalRevenue.currency
                ),
                createdAt = result.customer.createdAt,
                updatedAt = result.customer.updatedAt
            ),
            marketingConsents = result.marketingConsents.map { consent ->
                MarketingConsentResponse(
                    id = consent.id,
                    type = consent.type.name.lowercase(),
                    granted = consent.granted,
                    grantedAt = consent.grantedAt,
                    revokedAt = consent.revokedAt,
                    lastModifiedBy = consent.lastModifiedBy
                )
            },
            loyaltyTier = result.loyaltyTier.name.lowercase(),
            lifetimeValue = CustomerRevenueResponse(
                netAmount = result.lifetimeValue.netAmount.toDouble(),
                grossAmount = result.lifetimeValue.grossAmount.toDouble(),
                currency = result.lifetimeValue.currency
            )
        ))
    }

    @GetMapping("/{customerId}/vehicles")
    fun getCustomerVehicles(@PathVariable customerId: String): ResponseEntity<List<VehicleResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val vehicles = getCustomerVehiclesHandler.handle(
            customerId = CustomerId.fromString(customerId),
            studioId = principal.studioId
        )

        ResponseEntity.ok(vehicles)
    }

    @GetMapping("/{customerId}/visits")
    fun getCustomerVisits(
        @PathVariable customerId: String,
        @RequestParam(required = false, defaultValue = "1") page: Int,
        @RequestParam(required = false, defaultValue = "10") limit: Int
    ): ResponseEntity<CustomerVisitsResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = pl.detailing.crm.customer.visits.GetCustomerVisitsCommand(
            customerId = CustomerId(UUID.fromString(customerId)),
            studioId = principal.studioId,
            page = page,
            limit = limit
        )

        val result = getCustomerVisitsHandler.handle(command)

        ResponseEntity.ok(CustomerVisitsResponse(
            visits = result.visits.map { visit ->
                VisitResponse(
                    id = visit.id,
                    date = visit.date,
                    type = visit.type.name.lowercase(),
                    vehicleId = visit.vehicleId,
                    vehicleName = visit.vehicleName,
                    description = visit.description,
                    totalCost = VisitCostResponse(
                        netAmount = visit.totalCost.netAmount.toDouble(),
                        grossAmount = visit.totalCost.grossAmount.toDouble(),
                        currency = visit.totalCost.currency
                    ),
                    status = visit.status,
                    technician = visit.technician,
                    notes = visit.notes
                )
            },
            pagination = PaginationMeta(
                currentPage = result.pagination.currentPage,
                totalPages = result.pagination.totalPages,
                totalItems = result.pagination.totalItems,
                itemsPerPage = result.pagination.itemsPerPage
            )
        ))
    }

    @PatchMapping("/{customerId}")
    fun updateCustomer(
        @PathVariable customerId: String,
        @RequestBody request: UpdateCustomerRequest
    ): ResponseEntity<UpdateCustomerResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can update customers")
        }

        val command = pl.detailing.crm.customer.update.UpdateCustomerCommand(
            customerId = CustomerId(UUID.fromString(customerId)),
            studioId = principal.studioId,
            userId = principal.userId,
            firstName = request.firstName,
            lastName = request.lastName,
            email = request.contact.email,
            phone = request.contact.phone,
            homeAddress = request.homeAddress?.let {
                HomeAddress(
                    street = it.street,
                    city = it.city,
                    postalCode = it.postalCode,
                    country = it.country
                )
            }
        )

        val result = updateCustomerHandler.handle(command)

        ResponseEntity.ok(UpdateCustomerResponse(
            id = result.id,
            firstName = result.firstName,
            lastName = result.lastName,
            contact = CustomerContactResponse(
                email = result.email,
                phone = result.phone
            ),
            homeAddress = result.homeAddress?.let {
                HomeAddressResponse(
                    street = it.street,
                    city = it.city,
                    postalCode = it.postalCode,
                    country = it.country
                )
            },
            updatedAt = result.updatedAt
        ))
    }

    @PatchMapping("/{customerId}/company")
    fun updateCompany(
        @PathVariable customerId: String,
        @RequestBody request: UpdateCompanyRequest
    ): ResponseEntity<UpdateCompanyResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can update company data")
        }

        val command = pl.detailing.crm.customer.update.UpdateCompanyCommand(
            customerId = CustomerId(UUID.fromString(customerId)),
            studioId = principal.studioId,
            userId = principal.userId,
            name = request.name,
            nip = request.nip,
            regon = request.regon,
            address = CompanyAddress(
                street = request.address.street,
                city = request.address.city,
                postalCode = request.address.postalCode,
                country = request.address.country
            )
        )

        val result = updateCompanyHandler.handle(command)

        ResponseEntity.ok(UpdateCompanyResponse(
            id = result.id,
            name = result.name,
            nip = result.nip,
            regon = result.regon,
            address = CompanyAddressResponse(
                street = result.address.street,
                city = result.address.city,
                postalCode = result.address.postalCode,
                country = result.address.country
            )
        ))
    }

    @DeleteMapping("/{customerId}/company")
    fun deleteCompany(@PathVariable customerId: String): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can delete company data")
        }

        val command = pl.detailing.crm.customer.update.DeleteCompanyCommand(
            customerId = CustomerId(UUID.fromString(customerId)),
            studioId = principal.studioId,
            userId = principal.userId
        )

        deleteCompanyHandler.handle(command)

        ResponseEntity.noContent().build()
    }

    @PatchMapping("/{customerId}/notes")
    fun updateNotes(
        @PathVariable customerId: String,
        @RequestBody request: UpdateNotesRequest
    ): ResponseEntity<UpdateNotesResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = pl.detailing.crm.customer.update.UpdateNotesCommand(
            customerId = CustomerId(UUID.fromString(customerId)),
            studioId = principal.studioId,
            userId = principal.userId,
            notes = request.notes
        )

        val result = updateNotesHandler.handle(command)

        ResponseEntity.ok(UpdateNotesResponse(
            notes = result.notes,
            updatedAt = result.updatedAt
        ))
    }
}

data class CustomerResponse(
    val id: String,
    val firstName: String?,
    val lastName: String?,
    val email: String?,
    val phone: String?,
    val homeAddress: HomeAddressResponse?,
    val companyData: CompanyDataResponse?,
    val notes: String?,
    val isActive: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant
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
    val firstName: String?,
    val lastName: String?,
    val contact: CustomerContactResponse,
    val homeAddress: HomeAddressResponse?,
    val company: CompanyDetailsResponse?,
    val notes: String,
    val lastVisitDate: Instant?,
    val totalVisits: Int,
    val vehicleCount: Int,
    val totalRevenue: CustomerRevenueResponse,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class CustomerContactResponse(
    val email: String?,
    val phone: String?
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

data class CustomerDetailFullResponse(
    val customer: CustomerDetailResponse,
    val marketingConsents: List<MarketingConsentResponse>,
    val loyaltyTier: String,
    val lifetimeValue: CustomerRevenueResponse
)

data class MarketingConsentResponse(
    val id: String,
    val type: String,
    val granted: Boolean,
    val grantedAt: Instant?,
    val revokedAt: Instant?,
    val lastModifiedBy: String
)

// Update Customer DTOs
data class UpdateCustomerRequest(
    val firstName: String?,
    val lastName: String?,
    val contact: CustomerContactRequest,
    val homeAddress: HomeAddressRequest?
)

data class CustomerContactRequest(
    val email: String?,
    val phone: String?
)

data class HomeAddressRequest(
    val street: String,
    val city: String,
    val postalCode: String,
    val country: String
)

data class UpdateCustomerResponse(
    val id: String,
    val firstName: String?,
    val lastName: String?,
    val contact: CustomerContactResponse,
    val homeAddress: HomeAddressResponse?,
    val updatedAt: Instant
)

// Update Company DTOs
data class UpdateCompanyRequest(
    val name: String,
    val nip: String,
    val regon: String,
    val address: CompanyAddressRequest
)

data class CompanyAddressRequest(
    val street: String,
    val city: String,
    val postalCode: String,
    val country: String
)

data class UpdateCompanyResponse(
    val id: String,
    val name: String,
    val nip: String,
    val regon: String,
    val address: CompanyAddressResponse
)

// Update Notes DTOs
data class UpdateNotesRequest(
    val notes: String
)

data class UpdateNotesResponse(
    val notes: String,
    val updatedAt: Instant
)

// Customer Visits DTOs
data class CustomerVisitsResponse(
    val visits: List<VisitResponse>,
    val pagination: PaginationMeta
)

data class VisitResponse(
    val id: String,
    val date: Instant,
    val type: String,
    val vehicleId: String,
    val vehicleName: String,
    val description: String,
    val totalCost: VisitCostResponse,
    val status: String,
    val technician: String,
    val notes: String
)

data class VisitCostResponse(
    val netAmount: Double,
    val grossAmount: Double,
    val currency: String
)
