package pl.detailing.crm.statistics.category.assignservices

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.ServiceCategoryId
import pl.detailing.crm.shared.ServiceId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.statistics.category.infrastructure.CategoryServiceAssignmentRepository
import pl.detailing.crm.statistics.category.infrastructure.ServiceCategoryRepository
import java.util.UUID

@Service
class UnassignSingleServiceHandler(
    private val serviceCategoryRepository: ServiceCategoryRepository,
    private val categoryServiceAssignmentRepository: CategoryServiceAssignmentRepository,
    private val serviceRepository: ServiceRepository
) {

    /**
     * Idempotently removes a service assignment from a category.
     *
     * Returns without error if the service was not assigned to the category (idempotent).
     *
     * @throws EntityNotFoundException if category or service is not found in the studio
     */
    @Transactional
    suspend fun handle(categoryId: ServiceCategoryId, serviceId: ServiceId, studioId: StudioId) =
        withContext(Dispatchers.IO) {
            serviceCategoryRepository.findByIdAndStudioId(categoryId.value, studioId.value)
                ?: throw EntityNotFoundException("Category $categoryId not found")

            serviceRepository.findByIdAndStudioId(serviceId.value, studioId.value)
                ?: throw EntityNotFoundException("Service $serviceId not found")

            val rootServiceId = resolveRootServiceId(serviceId.value, studioId.value)

            val assignments = categoryServiceAssignmentRepository
                .findByCategoryIdAndStudioId(categoryId.value, studioId.value)
                .filter { it.serviceId == rootServiceId }

            if (assignments.isNotEmpty()) {
                categoryServiceAssignmentRepository.deleteAll(assignments)
            }
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
