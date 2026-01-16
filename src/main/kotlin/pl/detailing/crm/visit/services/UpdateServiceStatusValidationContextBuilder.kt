package pl.detailing.crm.visit.services

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import pl.detailing.crm.visit.infrastructure.VisitRepository

@Component
class UpdateServiceStatusValidationContextBuilder(
    private val visitRepository: VisitRepository
) {
    suspend fun build(command: UpdateServiceStatusCommand): UpdateServiceStatusValidationContext =
        withContext(Dispatchers.IO) {
            val visitDeferred = async {
                visitRepository.findByIdAndStudioId(
                    command.visitId.value,
                    command.studioId.value
                )?.toDomain()
            }

            val visit = visitDeferred.await()
            val serviceItem = visit?.serviceItems?.find { it.id == command.serviceItemId }

            UpdateServiceStatusValidationContext(
                studioId = command.studioId,
                visitId = command.visitId,
                serviceItemId = command.serviceItemId,
                newStatus = command.newStatus,
                visit = visit,
                serviceItem = serviceItem
            )
        }
}
