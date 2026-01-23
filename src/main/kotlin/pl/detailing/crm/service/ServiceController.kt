package pl.detailing.crm.service

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.service.create.CreateServiceCommand
import pl.detailing.crm.service.create.CreateServiceHandler
import pl.detailing.crm.service.create.CreateServiceRequest
import pl.detailing.crm.service.list.ListServicesHandler
import pl.detailing.crm.service.list.ServiceListItem
import pl.detailing.crm.service.update.UpdateServiceCommand
import pl.detailing.crm.service.update.UpdateServiceHandler
import pl.detailing.crm.service.update.UpdateServiceRequest
import pl.detailing.crm.shared.*

@RestController
@RequestMapping("/api/v1/services")
class ServiceController(
    private val createServiceHandler: CreateServiceHandler,
    private val updateServiceHandler: UpdateServiceHandler,
    private val listServicesHandler: ListServicesHandler
) {

    @GetMapping
    fun getServices(
        @RequestParam(required = false, defaultValue = "") search: String,
        @RequestParam(required = false, defaultValue = "1") page: Int,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
        @RequestParam(required = false, defaultValue = "false") showInactive: Boolean,
        @RequestParam(required = false) sortBy: String?,
        @RequestParam(required = false, defaultValue = "asc") sortDirection: String
    ): ResponseEntity<ServiceListResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        var services = listServicesHandler.handle(principal.studioId, showInactive)

        if (search.isNotBlank()) {
            services = services.filter { it.name.contains(search, ignoreCase = true) }
        }

        services = when (sortBy) {
            "name" -> if (sortDirection == "asc") {
                services.sortedBy { it.name }
            } else {
                services.sortedByDescending { it.name }
            }
            "basePriceNet" -> if (sortDirection == "asc") {
                services.sortedBy { it.basePriceNet }
            } else {
                services.sortedByDescending { it.basePriceNet }
            }
            else -> services
        }

        val totalItems = services.size
        val start = (page - 1) * limit
        val end = minOf(start + limit, totalItems)
        val paginatedServices = if (start < totalItems) {
            services.subList(start, end)
        } else {
            emptyList()
        }

        ResponseEntity.ok(ServiceListResponse(
            services = paginatedServices,
            pagination = PaginationInfo(
                currentPage = page,
                totalPages = (totalItems + limit - 1) / limit,
                totalItems = totalItems,
                itemsPerPage = limit
            )
        ))
    }

    @PostMapping
    fun createService(@RequestBody request: CreateServiceRequest): ResponseEntity<ServiceResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can create services")
        }

        val command = CreateServiceCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            name = request.name,
            basePriceNet = Money.fromCents(request.basePriceNet),
            vatRate = VatRate.fromInt(request.vatRate),
            requireManualPrice = request.requireManualPrice
        )

        val result = createServiceHandler.handle(command)

        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ServiceResponse(
                id = result.serviceId.toString(),
                name = result.name,
                basePriceNet = result.basePriceNet,
                vatRate = result.vatRate,
                isActive = true,
                requireManualPrice = result.requireManualPrice,
                createdAt = java.time.Instant.now().toString(),
                updatedAt = java.time.Instant.now().toString(),
                replacesServiceId = null
            ))
    }

    @PostMapping("/update")
    fun updateService(@RequestBody request: UpdateServiceRequest): ResponseEntity<ServiceResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Only OWNER and MANAGER can update services")
        }

        val command = UpdateServiceCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            oldServiceId = ServiceId.fromString(request.originalServiceId),
            name = request.name,
            basePriceNet = Money.fromCents(request.basePriceNet),
            vatRate = VatRate.fromInt(request.vatRate),
            requireManualPrice = request.requireManualPrice
        )

        val result = updateServiceHandler.handle(command)

        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ServiceResponse(
                id = result.newServiceId.toString(),
                name = result.name,
                basePriceNet = result.basePriceNet,
                vatRate = result.vatRate,
                isActive = true,
                requireManualPrice = result.requireManualPrice,
                createdAt = java.time.Instant.now().toString(),
                updatedAt = java.time.Instant.now().toString(),
                replacesServiceId = result.replacesServiceId.toString()
            ))
    }
}

data class ServiceResponse(
    val id: String,
    val name: String,
    val basePriceNet: Long,
    val vatRate: Int,
    val isActive: Boolean,
    val requireManualPrice: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val replacesServiceId: String?
)

data class ServiceListResponse(
    val services: List<ServiceListItem>,
    val pagination: PaginationInfo
)

data class PaginationInfo(
    val currentPage: Int,
    val totalPages: Int,
    val totalItems: Int,
    val itemsPerPage: Int
)