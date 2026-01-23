package pl.detailing.crm.service.create

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import pl.detailing.crm.service.infrastructure.ServiceRepository

@Component
class CreateServiceValidationContextBuilder(
    private val serviceRepository: ServiceRepository
) {
    suspend fun build(command: CreateServiceCommand): CreateServiceValidationContext =
        withContext(Dispatchers.IO) {
            val serviceExistsDeferred = async {
                serviceRepository.existsActiveByStudioIdAndName(
                    command.studioId.value,
                    command.name.trim()
                )
            }

            CreateServiceValidationContext(
                studioId = command.studioId,
                name = command.name,
                basePriceNet = command.basePriceNet,
                vatRate = command.vatRate,
                requireManualPrice = command.requireManualPrice,
                serviceNameExists = serviceExistsDeferred.await()
            )
        }
}