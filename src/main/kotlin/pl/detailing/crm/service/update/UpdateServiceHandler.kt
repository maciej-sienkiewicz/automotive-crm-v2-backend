// src/main/kotlin/pl/detailing/crm/service/update/UpdateServiceHandler.kt

package pl.detailing.crm.service.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.service.domain.Service as ServiceDomain
import pl.detailing.crm.service.infrastructure.ServiceEntity
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.*
import java.time.Instant

@Service
class UpdateServiceHandler(
    private val validatorComposite: UpdateServiceValidatorComposite,
    private val serviceRepository: ServiceRepository,
    private val auditService: AuditService
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

        val oldValues = mapOf(
            "name" to oldServiceEntity.name,
            "basePriceNet" to oldServiceEntity.basePriceNet.toString(),
            "vatRate" to oldServiceEntity.vatRate.toString(),
            "requireManualPrice" to oldServiceEntity.requireManualPrice.toString()
        )
        val newValues = mapOf(
            "name" to newService.name,
            "basePriceNet" to netAmount.amountInCents.toString(),
            "vatRate" to command.vatRate.rate.toString(),
            "requireManualPrice" to newService.requireManualPrice.toString()
        )
        val changes = auditService.computeChanges(oldValues, newValues)

        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName ?: "",
            module = AuditModule.SERVICE,
            entityId = newService.id.value.toString(),
            entityDisplayName = newService.name,
            action = AuditAction.UPDATE,
            changes = changes,
            metadata = mapOf("replacesServiceId" to command.oldServiceId.value.toString())
        ))

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