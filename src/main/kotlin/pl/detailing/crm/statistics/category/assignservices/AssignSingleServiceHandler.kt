package pl.detailing.crm.statistics.category.assignservices

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ServiceCategoryId
import pl.detailing.crm.shared.ServiceId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.statistics.category.infrastructure.CategoryServiceAssignmentEntity
import pl.detailing.crm.statistics.category.infrastructure.CategoryServiceAssignmentRepository
import pl.detailing.crm.statistics.category.infrastructure.ServiceCategoryRepository
import pl.detailing.crm.statistics.category.manual.ManualServiceCategoryAssignmentEntity
import pl.detailing.crm.statistics.category.manual.ManualServiceCategoryAssignmentRepository
import pl.detailing.crm.statistics.category.manual.ManualServiceRepository
import java.time.Instant
import java.util.UUID

@Service
class AssignSingleServiceHandler(
    private val serviceCategoryRepository: ServiceCategoryRepository,
    private val categoryServiceAssignmentRepository: CategoryServiceAssignmentRepository,
    private val serviceRepository: ServiceRepository,
    private val manualServiceRepository: ManualServiceRepository,
    private val manualServiceCategoryAssignmentRepository: ManualServiceCategoryAssignmentRepository
) {

    /**
     * Idempotently assigns a single service to a category.
     *
     * Accepts both catalog service UUIDs (from the [services] table) and manual
     * service UUIDs (from [manual_services], issued by the breakdown endpoint).
     * Enforces the one-service-per-category rule for both types.
     */
    @Transactional
    suspend fun handle(categoryId: ServiceCategoryId, serviceId: ServiceId, studioId: StudioId) =
        withContext(Dispatchers.IO) {
            val category = serviceCategoryRepository.findByIdAndStudioId(categoryId.value, studioId.value)
                ?: throw EntityNotFoundException("Category $categoryId not found")

            if (!category.isActive) {
                throw ConflictException("Category ${category.name} is inactive")
            }

            // Determine whether the UUID refers to a catalog service or a manual service.
            val catalogService = serviceRepository.findByIdAndStudioId(serviceId.value, studioId.value)

            if (catalogService != null) {
                assignCatalogService(serviceId.value, categoryId.value, studioId.value)
            } else {
                val manualService = manualServiceRepository.findByIdAndStudioId(serviceId.value, studioId.value)
                    ?: throw EntityNotFoundException("Service $serviceId not found")
                assignManualService(manualService.id, categoryId.value, studioId.value)
            }
        }

    private fun assignCatalogService(serviceId: UUID, categoryId: UUID, studioId: UUID) {
        val rootServiceId = resolveRootServiceId(serviceId, studioId)

        val alreadyInThisCategory = categoryServiceAssignmentRepository
            .findByCategoryIdAndStudioId(categoryId, studioId)
            .any { it.serviceId == rootServiceId }

        if (alreadyInThisCategory) return

        categoryServiceAssignmentRepository.deleteByServiceIdAndStudioId(rootServiceId, studioId)
        categoryServiceAssignmentRepository.save(
            CategoryServiceAssignmentEntity(
                id = UUID.randomUUID(),
                categoryId = categoryId,
                serviceId = rootServiceId,
                studioId = studioId,
                assignedAt = Instant.now()
            )
        )
    }

    private fun assignManualService(manualServiceId: UUID, categoryId: UUID, studioId: UUID) {
        val existing = manualServiceCategoryAssignmentRepository
            .findByManualServiceIdAndStudioId(manualServiceId, studioId)

        if (existing?.categoryId == categoryId) return  // already in this category — idempotent

        // Enforce one-category-per-manual-service: remove from any other category first
        if (existing != null) {
            manualServiceCategoryAssignmentRepository.deleteByManualServiceIdAndStudioId(
                manualServiceId, studioId
            )
        }

        manualServiceCategoryAssignmentRepository.save(
            ManualServiceCategoryAssignmentEntity(
                id = UUID.randomUUID(),
                studioId = studioId,
                manualServiceId = manualServiceId,
                categoryId = categoryId,
                assignedAt = Instant.now()
            )
        )
    }

    private fun resolveRootServiceId(serviceId: UUID, studioId: UUID): UUID {
        var currentId = serviceId
        while (true) {
            val svc = serviceRepository.findByIdAndStudioId(currentId, studioId)
                ?: throw EntityNotFoundException("Service $currentId not found in studio")
            if (svc.replacesServiceId == null) return currentId
            currentId = svc.replacesServiceId!!
        }
    }
}
