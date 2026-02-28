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
import pl.detailing.crm.statistics.category.manual.ManualServiceCategoryAssignmentRepository
import pl.detailing.crm.statistics.category.manual.ManualServiceRepository
import java.util.UUID

@Service
class UnassignSingleServiceHandler(
    private val serviceCategoryRepository: ServiceCategoryRepository,
    private val categoryServiceAssignmentRepository: CategoryServiceAssignmentRepository,
    private val serviceRepository: ServiceRepository,
    private val manualServiceRepository: ManualServiceRepository,
    private val manualServiceCategoryAssignmentRepository: ManualServiceCategoryAssignmentRepository
) {

    /**
     * Idempotently removes a service assignment from a category.
     *
     * Accepts both catalog service UUIDs and manual service UUIDs.
     * Returns without error if the service was not assigned (idempotent).
     */
    @Transactional
    suspend fun handle(categoryId: ServiceCategoryId, serviceId: ServiceId, studioId: StudioId) =
        withContext(Dispatchers.IO) {
            serviceCategoryRepository.findByIdAndStudioId(categoryId.value, studioId.value)
                ?: throw EntityNotFoundException("Category $categoryId not found")

            val catalogService = serviceRepository.findByIdAndStudioId(serviceId.value, studioId.value)

            if (catalogService != null) {
                unassignCatalogService(serviceId.value, categoryId.value, studioId.value)
            } else {
                manualServiceRepository.findByIdAndStudioId(serviceId.value, studioId.value)
                    ?: throw EntityNotFoundException("Service $serviceId not found")
                manualServiceCategoryAssignmentRepository.deleteByManualServiceIdAndStudioId(
                    serviceId.value, studioId.value
                )
            }
        }

    private fun unassignCatalogService(serviceId: UUID, categoryId: UUID, studioId: UUID) {
        val rootServiceId = resolveRootServiceId(serviceId, studioId)
        val assignments = categoryServiceAssignmentRepository
            .findByCategoryIdAndStudioId(categoryId, studioId)
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
