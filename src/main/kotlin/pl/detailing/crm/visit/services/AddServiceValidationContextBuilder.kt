package pl.detailing.crm.visit.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository

@Component
class AddServiceValidationContextBuilder(
    private val visitRepository: VisitRepository,
    private val serviceRepository: ServiceRepository
) {
    suspend fun build(command: AddServiceCommand): AddServiceValidationContext =
        withContext(Dispatchers.IO) {
            // Parallel database queries
            val visitDeferred = async {
                visitRepository.findByIdAndStudioId(
                    command.visitId.value,
                    command.studioId.value
                )?.toDomain()
            }

            val serviceDeferred = async {
                serviceRepository.findByIdAndStudioId(
                    command.serviceId.value,
                    command.studioId.value
                )?.toDomain()
            }

            AddServiceValidationContext(
                studioId = command.studioId,
                visitId = command.visitId,
                serviceId = command.serviceId,
                visit = visitDeferred.await(),
                service = serviceDeferred.await()
            )
        }
}
