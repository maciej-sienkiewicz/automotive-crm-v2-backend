package pl.detailing.crm.statistics.category.assignservices

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.statistics.category.infrastructure.CategoryServiceAssignmentEntity
import pl.detailing.crm.statistics.category.infrastructure.CategoryServiceAssignmentRepository
import pl.detailing.crm.statistics.category.infrastructure.ServiceCategoryRepository
import pl.detailing.crm.statistics.category.manual.ManualServiceCategoryAssignmentEntity
import pl.detailing.crm.statistics.category.manual.ManualServiceCategoryAssignmentRepository
import pl.detailing.crm.statistics.category.manual.ManualServiceRepository
import java.time.Instant
import java.util.UUID

@Service
class AssignServicesHandler(
    private val serviceCategoryRepository: ServiceCategoryRepository,
    private val categoryServiceAssignmentRepository: CategoryServiceAssignmentRepository,
    private val serviceRepository: ServiceRepository,
    private val manualServiceRepository: ManualServiceRepository,
    private val manualServiceCategoryAssignmentRepository: ManualServiceCategoryAssignmentRepository
) {
    /**
     * Replaces ALL service assignments for a category (catalog + manual).
     *
     * Each UUID in the command is resolved as either a catalog service (via the
     * [services] table) or a manual service (via [manual_services]). Unknown UUIDs
     * throw [EntityNotFoundException].
     *
     * An empty list removes all assignments of both types.
     */
    suspend fun handle(command: AssignServicesCommand) = withContext(Dispatchers.IO) {
        serviceCategoryRepository.findByIdAndStudioId(
            command.categoryId.value,
            command.studioId.value
        ) ?: throw EntityNotFoundException("Category ${command.categoryId} not found")

        // Classify each UUID as catalog or manual
        val catalogRootIds = mutableSetOf<UUID>()
        val manualIds = mutableSetOf<UUID>()

        for (serviceId in command.serviceIds) {
            val catalogService = serviceRepository.findByIdAndStudioId(serviceId.value, command.studioId.value)
            if (catalogService != null) {
                catalogRootIds.add(resolveRootServiceId(serviceId.value, command.studioId.value))
            } else {
                manualServiceRepository.findByIdAndStudioId(serviceId.value, command.studioId.value)
                    ?: throw EntityNotFoundException("Service ${serviceId.value} not found in studio")
                manualIds.add(serviceId.value)
            }
        }

        // Full replacement for catalog assignments
        categoryServiceAssignmentRepository.deleteAllByCategoryIdAndStudioId(
            command.categoryId.value,
            command.studioId.value
        )
        if (catalogRootIds.isNotEmpty()) {
            categoryServiceAssignmentRepository.saveAll(
                catalogRootIds.map { rootId ->
                    CategoryServiceAssignmentEntity(
                        id = UUID.randomUUID(),
                        categoryId = command.categoryId.value,
                        serviceId = rootId,
                        studioId = command.studioId.value,
                        assignedAt = Instant.now()
                    )
                }
            )
        }

        // Full replacement for manual assignments
        manualServiceCategoryAssignmentRepository.deleteAllByCategoryIdAndStudioId(
            command.categoryId.value,
            command.studioId.value
        )
        if (manualIds.isNotEmpty()) {
            manualServiceCategoryAssignmentRepository.saveAll(
                manualIds.map { manualId ->
                    ManualServiceCategoryAssignmentEntity(
                        id = UUID.randomUUID(),
                        studioId = command.studioId.value,
                        manualServiceId = manualId,
                        categoryId = command.categoryId.value,
                        assignedAt = Instant.now()
                    )
                }
            )
        }
    }

    private fun resolveRootServiceId(serviceId: UUID, studioId: UUID): UUID {
        var currentId = serviceId
        while (true) {
            val service = serviceRepository.findByIdAndStudioId(currentId, studioId)
                ?: throw EntityNotFoundException("Service $currentId not found in studio")
            if (service.replacesServiceId == null) return currentId
            currentId = service.replacesServiceId!!
        }
    }
}
