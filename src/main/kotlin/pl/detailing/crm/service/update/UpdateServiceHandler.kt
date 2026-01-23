// src/main/kotlin/pl/detailing/crm/service/update/UpdateServiceHandler.kt

package pl.detailing.crm.service.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.service.domain.Service as ServiceDomain
import pl.detailing.crm.service.infrastructure.ServiceEntity
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.*
import java.time.Instant

@Service
class UpdateServiceHandler(
    private val validatorComposite: UpdateServiceValidatorComposite,
    private val serviceRepository: ServiceRepository
) {

    @Transactional
    suspend fun handle(command: UpdateServiceCommand): UpdateServiceResult = withContext(Dispatchers.IO) {
        validatorComposite.validate(command)

        val oldServiceEntity = serviceRepository.findByIdAndStudioId(
            command.oldServiceId.value,
            command.studioId.value
        ) ?: throw EntityNotFoundException("Service not found")

        oldServiceEntity.isActive = false
        oldServiceEntity.updatedAt = Instant.now()
        serviceRepository.save(oldServiceEntity)

        val netAmount = command.basePriceNet
        val vatAmount = command.vatRate.calculateVatAmount(netAmount)
        val grossAmount = command.vatRate.calculateGrossAmount(netAmount)

        val newService = ServiceDomain(
            id = ServiceId.random(),
            studioId = command.studioId,
            name = command.name.trim(),
            basePriceNet = netAmount,
            vatRate = command.vatRate,
            isActive = true,
            requireManualPrice = command.requireManualPrice,
            replacesServiceId = command.oldServiceId,
            createdBy = UserId(oldServiceEntity.createdBy),
            updatedBy = command.userId,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        val newEntity = ServiceEntity.fromDomain(newService)
        serviceRepository.save(newEntity)

        UpdateServiceResult(
            oldServiceId = command.oldServiceId,
            newServiceId = newService.id,
            name = newService.name,
            basePriceNet = netAmount.amountInCents,
            vatRate = command.vatRate.rate,
            vatAmount = vatAmount.amountInCents,
            priceGross = grossAmount.amountInCents,
            requireManualPrice = newService.requireManualPrice,
            replacesServiceId = command.oldServiceId
        )
    }
}

data class UpdateServiceResult(
    val oldServiceId: ServiceId,
    val newServiceId: ServiceId,
    val name: String,
    val basePriceNet: Long,
    val vatRate: Int,
    val vatAmount: Long,
    val priceGross: Long,
    val requireManualPrice: Boolean,
    val replacesServiceId: ServiceId
)