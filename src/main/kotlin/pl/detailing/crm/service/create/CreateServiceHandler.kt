package pl.detailing.crm.service.create

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
class CreateServiceHandler(
    private val validatorComposite: CreateServiceValidatorComposite,
    private val serviceRepository: ServiceRepository
) {

    @Transactional
    suspend fun handle(command: CreateServiceCommand): CreateServiceResult = withContext(Dispatchers.IO) {
        validatorComposite.validate(command)

        val netAmount = command.basePriceNet
        val vatAmount = command.vatRate.calculateVatAmount(netAmount)
        val grossAmount = command.vatRate.calculateGrossAmount(netAmount)

        val service = ServiceDomain(
            id = ServiceId.random(),
            studioId = command.studioId,
            name = command.name.trim(),
            basePriceNet = netAmount,
            vatRate = command.vatRate,
            isActive = true,
            replacesServiceId = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        val entity = ServiceEntity.fromDomain(service)
        serviceRepository.save(entity)

        CreateServiceResult(
            serviceId = service.id,
            name = service.name,
            basePriceNet = netAmount.amountInCents,
            vatRate = command.vatRate.rate,
            vatAmount = vatAmount.amountInCents,
            priceGross = grossAmount.amountInCents
        )
    }
}

data class CreateServiceResult(
    val serviceId: ServiceId,
    val name: String,
    val basePriceNet: Long,
    val vatRate: Int,
    val vatAmount: Long,
    val priceGross: Long
)
