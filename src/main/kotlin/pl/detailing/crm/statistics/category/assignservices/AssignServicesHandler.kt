package pl.detailing.crm.statistics.category.assignservices

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.statistics.category.infrastructure.CategoryServiceAssignmentEntity
import pl.detailing.crm.statistics.category.infrastructure.CategoryServiceAssignmentRepository
import pl.detailing.crm.statistics.category.infrastructure.ServiceCategoryRepository
import java.time.Instant
import java.util.UUID

@Service
class AssignServicesHandler(
    private val serviceCategoryRepository: ServiceCategoryRepository,
    private val categoryServiceAssignmentRepository: CategoryServiceAssignmentRepository,
    private val serviceRepository: ServiceRepository
) {
    /**
     * Replaces all service assignments for a category.
     *
     * For each provided service ID, resolves the ROOT ancestor (following replaces_service_id
     * backward until null). Only root IDs are stored, so recursive CTE stats queries can
     * automatically include all current and future versions without needing assignment updates.
     */
    suspend fun handle(command: AssignServicesCommand) = withContext(Dispatchers.IO) {
        serviceCategoryRepository.findByIdAndStudioId(
            command.categoryId.value,
            command.studioId.value
        ) ?: throw EntityNotFoundException("Category ${command.categoryId} not found")

        val rootServiceIds: Set<UUID> = command.serviceIds
            .map { serviceId -> resolveRootServiceId(serviceId.value, command.studioId.value) }
            .toSet()

        // Full replacement: remove existing, add new
        categoryServiceAssignmentRepository.deleteAllByCategoryIdAndStudioId(
            command.categoryId.value,
            command.studioId.value
        )

        if (rootServiceIds.isNotEmpty()) {
            val assignments = rootServiceIds.map { rootServiceId ->
                CategoryServiceAssignmentEntity(
                    id = UUID.randomUUID(),
                    categoryId = command.categoryId.value,
                    serviceId = rootServiceId,
                    studioId = command.studioId.value,
                    assignedAt = Instant.now()
                )
            }
            categoryServiceAssignmentRepository.saveAll(assignments)
        }
    }

    /**
     * Traverses the replaces_service_id chain backward until the root service
     * (one with replaces_service_id = null) is found.
     *
     * Example: S3.replaces_service_id -> S2.replaces_service_id -> S1 (root, replaces_service_id = null)
     * Assigning S3 stores S1, so future stats include S1, S2, S3 and any S4+ created later.
     */
    private fun resolveRootServiceId(serviceId: UUID, studioId: UUID): UUID {
        var currentId = serviceId
        while (true) {
            val service = serviceRepository.findByIdAndStudioId(currentId, studioId)
                ?: throw EntityNotFoundException("Service $currentId not found in studio")
            if (service.replacesServiceId == null) {
                return currentId
            }
            currentId = service.replacesServiceId!!
        }
    }
}
