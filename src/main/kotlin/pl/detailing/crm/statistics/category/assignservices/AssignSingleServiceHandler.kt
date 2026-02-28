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
import java.time.Instant
import java.util.UUID

@Service
class AssignSingleServiceHandler(
    private val serviceCategoryRepository: ServiceCategoryRepository,
    private val categoryServiceAssignmentRepository: CategoryServiceAssignmentRepository,
    private val serviceRepository: ServiceRepository
) {

    /**
     * Idempotently assigns a single service to a category.
     *
     * Enforces the one-service-per-category invariant: if the service is already assigned
     * to a different category, it is moved to the requested category.
     *
     * Returns without error if the service is already assigned to this category (idempotent).
     *
     * @throws EntityNotFoundException if category or service is not found in the studio
     * @throws ConflictException if the category is inactive
     */
    @Transactional
    suspend fun handle(categoryId: ServiceCategoryId, serviceId: ServiceId, studioId: StudioId) =
        withContext(Dispatchers.IO) {
            val category = serviceCategoryRepository.findByIdAndStudioId(categoryId.value, studioId.value)
                ?: throw EntityNotFoundException("Category $categoryId not found")

            if (!category.isActive) {
                throw ConflictException("Category ${category.name} is inactive")
            }

            serviceRepository.findByIdAndStudioId(serviceId.value, studioId.value)
                ?: throw EntityNotFoundException("Service $serviceId not found")

            val rootServiceId = resolveRootServiceId(serviceId.value, studioId.value)

            // Already assigned to this category — idempotent, nothing to do
            val alreadyInThisCategory = categoryServiceAssignmentRepository
                .findByCategoryIdAndStudioId(categoryId.value, studioId.value)
                .any { it.serviceId == rootServiceId }

            if (alreadyInThisCategory) return@withContext

            // Enforce one-category-per-service: remove from any other category first
            categoryServiceAssignmentRepository.deleteByServiceIdAndStudioId(rootServiceId, studioId.value)

            categoryServiceAssignmentRepository.save(
                CategoryServiceAssignmentEntity(
                    id = UUID.randomUUID(),
                    categoryId = categoryId.value,
                    serviceId = rootServiceId,
                    studioId = studioId.value,
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
