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
import pl.detailing.crm.customer.list.CustomerListQuery
import pl.detailing.crm.customer.list.ListCustomersHandler
import pl.detailing.crm.customer.notes.CustomerNoteItem
import pl.detailing.crm.customer.revenuesummary.GetCustomerRevenueSummaryCommand
import pl.detailing.crm.customer.revenuesummary.GetCustomerRevenueSummaryHandler
import pl.detailing.crm.customer.vehicles.GetCustomerVehiclesHandler
import pl.detailing.crm.customer.vehicles.VehicleResponse

import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.pii.Pii
import pl.detailing.crm.shared.pii.PiiAccessContext
import pl.detailing.crm.vehicle.VehicleDataResponse
import pl.detailing.crm.vehicle.VehicleResponse as VehicleCreateResponse
import pl.detailing.crm.vehicle.create.CreateVehicleCommand
import pl.detailing.crm.vehicle.create.CreateVehicleHandler
import java.time.Instant
import java.util.UUID
import java.time.temporal.ChronoUnit
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission

@RestController
@RequestMapping("/api/v1/customers")
@RequiresPermission(Permission.CUSTOMERS_VIEW)
class CustomerController(
    private val createCustomerHandler: CreateCustomerHandler,
    private val listCustomersHandler: ListCustomersHandler,
    private val getCustomerByIdHandler: GetCustomerByIdHandler,
    private val getCustomerVehiclesHandler: GetCustomerVehiclesHandler,
    private val createVehicleHandler: CreateVehicleHandler,
    private val getCustomerDetailHandler: pl.detailing.crm.customer.detail.GetCustomerDetailHandler,
    private val updateCustomerHandler: pl.detailing.crm.customer.update.UpdateCustomerHandler,
    private val updateCompanyHandler: pl.detailing.crm.customer.update.UpdateCompanyHandler,
    private val deleteCompanyHandler: pl.detailing.crm.customer.update.DeleteCompanyHandler,
    private val getCustomerRevenueSummaryHandler: GetCustomerRevenueSummaryHandler
) {
    @GetMapping
    fun getCustomers(
        @RequestParam(required = false, defaultValue = "") search: String,
        @RequestParam(required = false, defaultValue = "1") page: Int,
        @RequestParam(required = false, defaultValue = "10") limit: Int,
        @RequestParam(required = false) sortBy: String?,
        @RequestParam(required = false, defaultValue = "asc") sortDirection: String,
        @RequestParam(required = false) customerType: String?,
        @RequestParam(required = false) services: List<String>?,
        @RequestParam(required = false) lastVisitWithinDays: Int?,
        @RequestParam(required = false) notVisitedSinceDays: Int?,
        @RequestParam(required = false) vehicleBrand: String?,
        @RequestParam(required = false) vehicleModel: String?,
        @RequestParam(required = false) minRevenue: Double?,
        @RequestParam(required = false) maxRevenue: Double?,
        @RequestParam(required = false) minVisits: Int?,
        @RequestParam(required = false) maxVisits: Int?
    ): ResponseEntity<CustomerListResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val serviceIds = services
            ?.mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }

        val query = CustomerListQuery(
            vehicleBrand = vehicleBrand?.takeIf { it.isNotBlank() },
            vehicleModel = vehicleModel?.takeIf { it.isNotBlank() },
            serviceIds = serviceIds
        )

        // Same decision the serializer uses — resolved once per request by PiiAccessFilter.
        val canViewPii = PiiAccessContext.isGranted()
        var customers = listCustomersHandler.handle(principal.studioId, query)

        if (search.isNotBlank()) {
            val normalizedSearch = search.replace("\\s".toRegex(), "")
            // Oracle guard: without the personal-data permission, searching must not match
            // masked fields — otherwise result presence lets an attacker brute-force a phone
            // number or e-mail despite the response being masked. Only the (unmasked)
            // company name remains searchable.
            customers = if (canViewPii) customers.filter {
                (it.firstName?.contains(search, ignoreCase = true) ?: false) ||
                (it.lastName?.contains(search, ignoreCase = true) ?: false) ||
                (it.contact.email?.contains(search, ignoreCase = true) ?: false) ||
                (it.contact.phone?.replace("\\s".toRegex(), "")?.contains(normalizedSearch, ignoreCase = true) ?: false) ||
                (it.company?.name?.contains(search, ignoreCase = true) ?: false) ||
                (it.company?.nip?.replace("\\s".toRegex(), "")?.contains(normalizedSearch, ignoreCase = true) ?: false)
            } else customers.filter {
                it.company?.name?.contains(search, ignoreCase = true) ?: false
            }
        }

        if (!customerType.isNullOrBlank() && customerType != "all") {
            customers = customers.filter {
                if (customerType == "business") it.company != null else it.company == null
            }
        }

        if (lastVisitWithinDays != null) {
            val cutoff = Instant.now().minus(lastVisitWithinDays.toLong(), ChronoUnit.DAYS)
            customers = customers.filter { it.lastVisitDate != null && it.lastVisitDate.isAfter(cutoff) }
        }

        if (notVisitedSinceDays != null) {
            val cutoff = Instant.now().minus(notVisitedSinceDays.toLong(), ChronoUnit.DAYS)
            customers = customers.filter { it.lastVisitDate == null || it.lastVisitDate.isBefore(cutoff) }
        }

        if (minRevenue != null) {
            customers = customers.filter { it.totalRevenue.grossAmount.toDouble() >= minRevenue }
        }

        if (maxRevenue != null) {
            customers = customers.filter { it.totalRevenue.grossAmount.toDouble() <= maxRevenue }
        }

        if (minVisits != null) {
            customers = customers.filter { it.totalVisits >= minVisits }
        }

        if (maxVisits != null) {
            customers = customers.filter { it.totalVisits <= maxVisits }
        }

        // Sorting by a masked field is a (weak) oracle too — alphabetical order of hidden
        // names is still information. Without the permission, name sorts fall back to createdAt.
        val nonPiiSorts = setOf("lastVisitDate", "totalVisits", "totalRevenue", "vehicleCount", "createdAt")
        val effectiveSortBy = if (!canViewPii && sortBy !in nonPiiSorts) "createdAt" else sortBy
        customers = when (effectiveSortBy) {
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

        // When sorting by revenue explicitly, skip the default lastVisitDate secondary sort
        // so the user's chosen order is preserved as the primary criterion.
        if (sortBy != "totalRevenue") {
            customers = customers.sortedWith(compareByDescending(nullsLast()) { it.lastVisitDate })
        }

        // Primary sort: customers with both firstName and lastName filled in come first.
        // Kotlin's sort is stable, so the secondary sort order is preserved within each group.
        customers = customers.sortedByDescending { !it.firstName.isNullOrBlank() && !it.lastName.isNullOrBlank() }

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
                HomeAddressResponse(street = it.street, city = it.city, postalCode = it.postalCode, country = it.country)
            },
            company = result.company?.let {
                CompanyDetailsResponse(
                    id = it.id,
                    name = it.name,
                    nip = it.nip,
                    regon = it.regon,
                    address = it.address?.let { addr ->
                        CompanyAddressResponse(street = addr.street, city = addr.city, postalCode = addr.postalCode, country = addr.country)
                    }
                )
            },
            notes = result.notes.map { it.toResponse() },
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
    @RequiresPermission(Permission.CUSTOMERS_MANAGE)
    fun createCustomer(@RequestBody request: CreateCustomerRequest): ResponseEntity<CustomerResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = CreateCustomerCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
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
            }
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
                    HomeAddressResponse(street = it.street, city = it.city, postalCode = it.postalCode, country = it.country)
                },
                companyData = request.companyData?.let {
                    CompanyDataResponse(
                        name = it.name,
                        nip = it.nip,
                        regon = it.regon,
                        address = it.address?.let { addr ->
                            CompanyAddressResponse(street = addr.street, city = addr.city, postalCode = addr.postalCode, country = addr.country)
                        }
                    )
                },
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
                    HomeAddressResponse(street = it.street, city = it.city, postalCode = it.postalCode, country = it.country)
                },
                company = result.customer.company?.let {
                    CompanyDetailsResponse(
                        id = it.id,
                        name = it.name,
                        nip = it.nip,
                        regon = it.regon,
                        address = it.address?.let { addr ->
                            CompanyAddressResponse(street = addr.street, city = addr.city, postalCode = addr.postalCode, country = addr.country)
                        }
                    )
                },
                notes = result.customer.notes.map { it.toResponse() },
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

    @PostMapping("/{customerId}/vehicles")
    @RequiresPermission(Permission.VISITS_CREATE)
    fun createCustomerVehicle(
        @PathVariable customerId: String,
        @RequestBody request: CreateCustomerVehicleRequest
    ): ResponseEntity<VehicleCreateResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = CreateVehicleCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
            ownerIds = listOf(CustomerId.fromString(customerId)),
            licensePlate = request.licensePlate?.takeIf { it.isNotBlank() },
            brand = request.make,
            model = request.model,
            yearOfProduction = request.year,
            color = request.color?.takeIf { it.isNotBlank() },
            paintType = null,
            currentMileage = request.mileage
        )

        val result = createVehicleHandler.handle(command)

        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(VehicleCreateResponse(
                data = VehicleDataResponse(
                    id = result.vehicleId.toString(),
                    licensePlate = result.licensePlate,
                    brand = result.brand,
                    model = result.model,
                    yearOfProduction = result.yearOfProduction,
                    color = result.color,
                    paintType = result.paintType,
                    currentMileage = result.currentMileage.toLong(),
                    status = result.status.name.lowercase(),
                    ownerIds = result.ownerIds.map { it.toString() },
                    createdAt = Instant.now(),
                    updatedAt = Instant.now()
                )
            ))
    }

    @GetMapping("/{customerId}/revenue-summary")
    fun getCustomerRevenueSummary(
        @PathVariable customerId: String,
        @RequestParam(required = false, defaultValue = "12") months: Int
    ): ResponseEntity<RevenueSummaryResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = GetCustomerRevenueSummaryCommand(
            customerId = CustomerId(UUID.fromString(customerId)),
            studioId = principal.studioId,
            months = months
        )

        val result = getCustomerRevenueSummaryHandler.handle(command)

        ResponseEntity.ok(RevenueSummaryResponse(
            buckets = result.buckets.map { bucket ->
                RevenueBucketResponse(
                    year = bucket.year,
                    month = bucket.month,
                    grossAmount = bucket.grossAmount,
                    currency = bucket.currency,
                    visitCount = bucket.visitCount
                )
            },
            total = RevenueTotalResponse(
                grossAmount = result.total.grossAmount,
                netAmount = result.total.netAmount,
                currency = result.total.currency,
                visitCount = result.total.visitCount
            ),
            period = RevenuePeriodResponse(
                from = result.period.from,
                to = result.period.to
            )
        ))
    }

    @PatchMapping("/{customerId}")
    @RequiresPermission(Permission.CUSTOMERS_MANAGE)
    fun updateCustomer(
        @PathVariable customerId: String,
        @RequestBody request: UpdateCustomerRequest
    ): ResponseEntity<UpdateCustomerResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = pl.detailing.crm.customer.update.UpdateCustomerCommand(
            customerId = CustomerId(UUID.fromString(customerId)),
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
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
                HomeAddressResponse(street = it.street, city = it.city, postalCode = it.postalCode, country = it.country)
            },
            updatedAt = result.updatedAt
        ))
    }

    @PatchMapping("/{customerId}/company")
    @RequiresPermission(Permission.CUSTOMERS_MANAGE)
    fun updateCompany(
        @PathVariable customerId: String,
        @RequestBody request: UpdateCompanyRequest
    ): ResponseEntity<UpdateCompanyResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = pl.detailing.crm.customer.update.UpdateCompanyCommand(
            customerId = CustomerId(UUID.fromString(customerId)),
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName,
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
    @RequiresPermission(Permission.CUSTOMERS_MANAGE)
    fun deleteCompany(@PathVariable customerId: String): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = pl.detailing.crm.customer.update.DeleteCompanyCommand(
            customerId = CustomerId(UUID.fromString(customerId)),
            studioId = principal.studioId,
            userId = principal.userId,
            userName = principal.fullName
        )

        deleteCompanyHandler.handle(command)

        ResponseEntity.noContent().build()
    }
}

private fun CustomerNoteItem.toResponse() = NoteItemResponse(
    id = id,
    content = content,
    createdBy = createdBy,
    createdByName = createdByName,
    createdAt = createdAt,
    updatedAt = updatedAt
)

data class NoteItemResponse(
    val id: String,
    val content: String,
    val createdBy: String,
    val createdByName: String,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class CustomerResponse(
    val id: String,
    @Pii val firstName: String?,
    @Pii val lastName: String?,
    @Pii val email: String?,
    @Pii val phone: String?,
    val homeAddress: HomeAddressResponse?,
    val companyData: CompanyDataResponse?,
    val isActive: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class HomeAddressResponse(
    @Pii val street: String,
    @Pii val city: String,
    @Pii val postalCode: String,
    val country: String
)

data class CompanyDataResponse(
    val name: String,
    @Pii val nip: String?,
    val regon: String?,
    val address: CompanyAddressResponse?
)

data class CompanyAddressResponse(
    @Pii val street: String,
    @Pii val city: String,
    @Pii val postalCode: String,
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
    @Pii val firstName: String?,
    @Pii val lastName: String?,
    val contact: CustomerContactResponse,
    val homeAddress: HomeAddressResponse?,
    val company: CompanyDetailsResponse?,
    val notes: List<NoteItemResponse>,
    val lastVisitDate: Instant?,
    val totalVisits: Int,
    val vehicleCount: Int,
    val totalRevenue: CustomerRevenueResponse,
    val createdAt: Instant,
    val updatedAt: Instant
)

data class CustomerContactResponse(
    @Pii val email: String?,
    @Pii val phone: String?
)

data class CompanyDetailsResponse(
    val id: String,
    val name: String,
    @Pii val nip: String?,
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
    @Pii val firstName: String?,
    @Pii val lastName: String?,
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
    @Pii val nip: String,
    val regon: String,
    val address: CompanyAddressResponse
)

// Revenue Summary DTOs
data class RevenueSummaryResponse(
    val buckets: List<RevenueBucketResponse>,
    val total: RevenueTotalResponse,
    val period: RevenuePeriodResponse
)

data class RevenueBucketResponse(
    val year: Int,
    val month: Int,
    val grossAmount: Long,
    val currency: String,
    val visitCount: Int
)

data class RevenueTotalResponse(
    val grossAmount: Long,
    val netAmount: Long,
    val currency: String,
    val visitCount: Int
)

data class RevenuePeriodResponse(
    val from: String,
    val to: String
)

data class CreateCustomerVehicleRequest(
    val make: String,
    val model: String,
    val year: Int?,
    val licensePlate: String?,
    val color: String?,
    val mileage: Int
)
