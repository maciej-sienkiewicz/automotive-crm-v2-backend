package pl.detailing.crm.service.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.service.domain.ServicePackageItem
import pl.detailing.crm.service.domain.ServicePackageItemId
import pl.detailing.crm.service.domain.Service as ServiceDomain
import pl.detailing.crm.service.infrastructure.ServiceEntity
import pl.detailing.crm.service.infrastructure.ServicePackageItemEntity
import pl.detailing.crm.service.infrastructure.ServicePackageItemRepository
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.*
import java.time.Instant

@Service
class UpdatePackageHandler(
    private val serviceRepository: ServiceRepository,
    private val packageItemRepository: ServicePackageItemRepository,
    private val auditService: AuditService
) {

    @Transactional
    suspend fun handle(command: UpdatePackageCommand): UpdateServiceResult = withContext(Dispatchers.IO) {
        val oldPackageEntity = serviceRepository.findByIdAndStudioId(
            command.originalPackageId.value,
            command.studioId.value
        ) ?: throw EntityNotFoundException("Pakiet nie został znaleziony")

        if (!oldPackageEntity.isPackage) {
            throw ValidationException("Podana usługa nie jest pakietem")
        }
        if (!oldPackageEntity.isActive) {
            throw ValidationException("Nie można aktualizować zarchiwizowanego pakietu")
        }

        validatePackageItems(command)

        oldPackageEntity.isActive = false
        oldPackageEntity.updatedAt = Instant.now()
        serviceRepository.save(oldPackageEntity)

        // Manual-price packages must not carry a catalog price — any price sent by the client is dropped
        val netAmount = if (command.requireManualPrice) Money.ZERO else command.basePriceNet
        val vatAmount = command.vatRate.calculateVatAmount(netAmount)
        val grossAmount = command.vatRate.calculateGrossAmount(netAmount)

        val newPackage = ServiceDomain(
            id = ServiceId.random(),
            studioId = command.studioId,
            name = command.name.trim(),
            basePriceNet = netAmount,
            vatRate = command.vatRate,
            isActive = true,
            requireManualPrice = command.requireManualPrice,
            isPackage = true,
            replacesServiceId = command.originalPackageId,
            createdBy = UserId(oldPackageEntity.createdBy),
            updatedBy = command.userId,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )
        serviceRepository.save(ServiceEntity.fromDomain(newPackage))

        val itemEntities = command.serviceIds.mapIndexed { index, serviceId ->
            val serviceEntity = serviceRepository.findByIdAndStudioId(serviceId.value, command.studioId.value)
                ?: throw EntityNotFoundException("Usługa ${serviceId.value} nie została znaleziona")
            ServicePackageItemEntity.fromDomain(
                ServicePackageItem(
                    id = ServicePackageItemId.random(),
                    packageId = newPackage.id,
                    serviceId = serviceId,
                    serviceName = serviceEntity.name,
                    studioId = command.studioId,
                    position = index,
                    createdAt = Instant.now()
                )
            )
        }
        packageItemRepository.saveAll(itemEntities)

        val oldValues = mapOf(
            "name" to oldPackageEntity.name,
            "basePriceNet" to oldPackageEntity.basePriceNet.toString(),
            "vatRate" to oldPackageEntity.vatRate.toString(),
            "requireManualPrice" to oldPackageEntity.requireManualPrice.toString()
        )
        val newValues = mapOf(
            "name" to newPackage.name,
            "basePriceNet" to netAmount.amountInCents.toString(),
            "vatRate" to command.vatRate.rate.toString(),
            "requireManualPrice" to newPackage.requireManualPrice.toString()
        )
        val changes = auditService.computeChanges(oldValues, newValues)

        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName ?: "",
            module = AuditModule.SERVICE,
            entityId = newPackage.id.value.toString(),
            entityDisplayName = newPackage.name,
            action = AuditAction.UPDATE,
            changes = changes,
            metadata = mapOf("replacesServiceId" to command.originalPackageId.value.toString())
        ))

        UpdateServiceResult(
            oldServiceId = command.originalPackageId,
            newServiceId = newPackage.id,
            name = newPackage.name,
            basePriceNet = netAmount.amountInCents,
            vatRate = command.vatRate.rate,
            vatAmount = vatAmount.amountInCents,
            priceGross = grossAmount.amountInCents,
            requireManualPrice = newPackage.requireManualPrice,
            replacesServiceId = command.originalPackageId,
            affectedPackages = emptyList()
        )
    }

    private fun validatePackageItems(command: UpdatePackageCommand) {
        if (command.serviceIds.size < 2) {
            throw ValidationException("Pakiet musi zawierać co najmniej 2 usługi")
        }
        if (command.serviceIds.distinct().size != command.serviceIds.size) {
            throw ValidationException("Pakiet nie może zawierać duplikatów usług")
        }

        val serviceEntities = serviceRepository.findAllById(command.serviceIds.map { it.value })

        if (serviceEntities.size != command.serviceIds.size) {
            throw ValidationException("Jedna lub więcej usług nie istnieje")
        }

        serviceEntities.forEach { entity ->
            if (entity.studioId != command.studioId.value) {
                throw ValidationException("Usługa ${entity.id} nie należy do tego studia")
            }
            if (!entity.isActive) {
                throw ValidationException("Usługa '${entity.name}' jest zarchiwizowana i nie może być dodana do pakietu")
            }
            if (entity.isPackage) {
                throw ValidationException("Pakiet '${entity.name}' nie może być składową innego pakietu")
            }
        }
    }
}
