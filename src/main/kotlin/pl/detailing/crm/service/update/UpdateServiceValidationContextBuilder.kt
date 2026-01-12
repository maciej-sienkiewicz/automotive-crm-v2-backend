package pl.detailing.crm.service.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import pl.detailing.crm.service.infrastructure.ServiceRepository

@Component
class UpdateServiceValidationContextBuilder(
    private val serviceRepository: ServiceRepository
) {
    suspend fun build(command: UpdateServiceCommand): UpdateServiceValidationContext =
        withContext(Dispatchers.IO) {
            val oldServiceDeferred = async {
                serviceRepository.findByIdAndStudioId(
                    command.oldServiceId.value,
                    command.studioId.value
                )
            }

            val nameConflictDeferred = async {
                val existing = serviceRepository.findActiveByStudioIdAndName(
                    command.studioId.value,
                    command.name.trim()
                )
                existing != null && existing.id != command.oldServiceId.value
            }

            UpdateServiceValidationContext(
                studioId = command.studioId,
                oldServiceId = command.oldServiceId,
                oldService = oldServiceDeferred.await(),
                name = command.name,
                basePriceNet = command.basePriceNet,
                vatRate = command.vatRate,
                nameConflictExists = nameConflictDeferred.await()
            )
        }
}