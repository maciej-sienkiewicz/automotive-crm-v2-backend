package pl.detailing.crm.service

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.service.create.CreatePackageHandler
import pl.detailing.crm.service.create.CreatePackageCommand
import pl.detailing.crm.service.create.CreatePackageRequest
import pl.detailing.crm.service.create.CreateServiceCommand
import pl.detailing.crm.service.create.CreateServiceHandler
import pl.detailing.crm.service.create.CreateServiceRequest
import pl.detailing.crm.service.infrastructure.ServicePackageItemRepository
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.service.list.ListServicesHandler
import pl.detailing.crm.service.list.PackageItemDto
import pl.detailing.crm.service.list.ServiceListItem
import pl.detailing.crm.service.archive.ArchiveServiceCommand
import pl.detailing.crm.service.archive.ArchiveServiceHandler
import pl.detailing.crm.service.update.AffectedPackage
import pl.detailing.crm.service.update.UpdatePackageCommand
import pl.detailing.crm.service.update.UpdatePackageHandler
import pl.detailing.crm.service.update.UpdatePackageRequest
import pl.detailing.crm.service.update.UpdateServiceCommand
import pl.detailing.crm.service.update.UpdateServiceHandler
import pl.detailing.crm.service.update.UpdateServiceRequest
import pl.detailing.crm.shared.*

@RestController
@RequestMapping("/api/v1/services")
class ServiceController(
    private val createServiceHandler: CreateServiceHandler,
    private val createPackageHandler: CreatePackageHandler,
    private val updateServiceHandler: UpdateServiceHandler,
    private val updatePackageHandler: UpdatePackageHandler,
    private val archiveServiceHandler: ArchiveServiceHandler,
    private val listServicesHandler: ListServicesHandler,
    private val serviceRepository: ServiceRepository,
    private val packageItemRepository: ServicePackageItemRepository
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
            throw ForbiddenException("Tylko właściciel i menedżer mogą tworzyć usługi")
        }

        val command = CreateServiceCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            name = request.name,
            basePriceNet = Money.fromCents(request.basePriceNet),
            vatRate = VatRate.fromInt(request.vatRate),
            requireManualPrice = request.requireManualPrice,
            userName = principal.fullName
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
                isPackage = false,
                packageItems = null,
                affectedPackages = emptyList(),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
                replacesServiceId = null
            ))
    }

    @PatchMapping("/{serviceId}/archive")
    fun archiveService(
        @PathVariable serviceId: String
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Tylko właściciel i menedżer mogą archiwizować usługi")
        }

        archiveServiceHandler.handle(
            ArchiveServiceCommand(
                studioId = principal.studioId,
                serviceId = ServiceId.fromString(serviceId),
                userId = principal.userId
            )
        )

        ResponseEntity.noContent().build()
    }

    @PostMapping("/packages")
    fun createPackage(@RequestBody request: CreatePackageRequest): ResponseEntity<ServiceResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Tylko właściciel i menedżer mogą tworzyć pakiety")
        }

        val command = CreatePackageCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            name = request.name,
            basePriceNet = Money.fromCents(request.basePriceNet),
            vatRate = VatRate.fromInt(request.vatRate),
            requireManualPrice = request.requireManualPrice,
            serviceIds = request.serviceIds.map { ServiceId.fromString(it) },
            userName = principal.fullName
        )

        val result = createPackageHandler.handle(command)
        val packageItems = packageItemRepository.findByPackageId(result.serviceId.value)
            .map { PackageItemDto(serviceId = it.serviceId.toString(), serviceName = it.serviceName, position = it.position) }

        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ServiceResponse(
                id = result.serviceId.toString(),
                name = result.name,
                basePriceNet = result.basePriceNet,
                vatRate = result.vatRate,
                isActive = true,
                requireManualPrice = result.requireManualPrice,
                isPackage = true,
                packageItems = packageItems,
                affectedPackages = emptyList(),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
                replacesServiceId = null
            ))
    }

    @PostMapping("/packages/update")
    fun updatePackage(@RequestBody request: UpdatePackageRequest): ResponseEntity<ServiceResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Tylko właściciel i menedżer mogą aktualizować pakiety")
        }

        val command = UpdatePackageCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            originalPackageId = ServiceId.fromString(request.originalPackageId),
            name = request.name,
            basePriceNet = Money.fromCents(request.basePriceNet),
            vatRate = VatRate.fromInt(request.vatRate),
            requireManualPrice = request.requireManualPrice,
            serviceIds = request.serviceIds.map { ServiceId.fromString(it) },
            userName = principal.fullName
        )

        val result = updatePackageHandler.handle(command)
        val packageItems = packageItemRepository.findByPackageId(result.newServiceId.value)
            .map { PackageItemDto(serviceId = it.serviceId.toString(), serviceName = it.serviceName, position = it.position) }

        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(ServiceResponse(
                id = result.newServiceId.toString(),
                name = result.name,
                basePriceNet = result.basePriceNet,
                vatRate = result.vatRate,
                isActive = true,
                requireManualPrice = result.requireManualPrice,
                isPackage = true,
                packageItems = packageItems,
                affectedPackages = emptyList(),
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
                replacesServiceId = result.replacesServiceId.toString()
            ))
    }

    @PostMapping("/packages/{packageId}/sync-item-name")
    fun syncPackageItemName(
        @PathVariable packageId: String,
        @RequestBody request: SyncPackageItemNameRequest
    ): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Tylko właściciel i menedżer mogą synchronizować pakiety")
        }

        val pkgId = ServiceId.fromString(packageId)
        serviceRepository.findByIdAndStudioId(pkgId.value, principal.studioId.value)
            ?: throw EntityNotFoundException("Pakiet nie został znaleziony")

        val serviceId = ServiceId.fromString(request.serviceId)
        val items = packageItemRepository.findByPackageId(pkgId.value)
        val item = items.find { it.serviceId == serviceId.value }
            ?: throw EntityNotFoundException("Usługa nie jest składową tego pakietu")

        item.serviceName = request.newName
        packageItemRepository.save(item)

        ResponseEntity.noContent().build()
    }

    @PostMapping("/update")
    fun updateService(@RequestBody request: UpdateServiceRequest): ResponseEntity<ServiceResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (principal.role != UserRole.OWNER && principal.role != UserRole.MANAGER) {
            throw ForbiddenException("Tylko właściciel i menedżer mogą aktualizować usługi")
        }

        val command = UpdateServiceCommand(
            studioId = principal.studioId,
            userId = principal.userId,
            oldServiceId = ServiceId.fromString(request.originalServiceId),
            name = request.name,
            basePriceNet = Money.fromCents(request.basePriceNet),
            vatRate = VatRate.fromInt(request.vatRate),
            requireManualPrice = request.requireManualPrice,
            userName = principal.fullName
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
                isPackage = false,
                packageItems = null,
                affectedPackages = result.affectedPackages,
                createdAt = Instant.now(),
                updatedAt = Instant.now(),
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
    val isPackage: Boolean,
    val packageItems: List<PackageItemDto>?,
    val affectedPackages: List<AffectedPackage>,
    val createdAt: Instant,
    val updatedAt: Instant,
    val replacesServiceId: String?
)

data class SyncPackageItemNameRequest(
    val serviceId: String,
    val newName: String
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