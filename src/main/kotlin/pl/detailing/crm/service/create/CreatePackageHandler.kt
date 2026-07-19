package pl.detailing.crm.service.create

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
class CreatePackageHandler(
    private val validatorComposite: CreateServiceValidatorComposite,
    private val serviceRepository: ServiceRepository,
    private val packageItemRepository: ServicePackageItemRepository,
    private val auditService: AuditService
) {

    @Transactional
    suspend fun handle(command: CreatePackageCommand): CreateServiceResult = withContext(Dispatchers.IO) {
        validatePackageItems(command)

        val createCommand = CreateServiceCommand(
            studioId = command.studioId,
            userId = command.userId,
            name = command.name,
            basePriceNet = command.basePriceNet,
            basePriceGross = command.basePriceGross,
            vatRate = command.vatRate,
            requireManualPrice = command.requireManualPrice,
            userName = command.userName
        )
        validatorComposite.validate(createCommand)

        // Manual-price packages must not carry a catalog price — any price sent by the client is dropped
        val netAmount = if (command.requireManualPrice) Money.ZERO else command.basePriceNet
        val grossAmount = if (command.requireManualPrice) Money.ZERO
            else command.vatRate.resolveGrossAmount(netAmount, command.basePriceGross)
        val vatAmount = grossAmount.minus(netAmount)

        val packageService = ServiceDomain(
            id = ServiceId.random(),
            studioId = command.studioId,
            name = command.name.trim(),
            basePriceNet = netAmount,
            basePriceGross = grossAmount,
            vatRate = command.vatRate,
            isActive = true,
            requireManualPrice = command.requireManualPrice,
            isPackage = true,
            replacesServiceId = null,
            createdBy = command.userId,
            updatedBy = command.userId,
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        serviceRepository.save(ServiceEntity.fromDomain(packageService))

        val itemEntities = command.serviceIds.mapIndexed { index, serviceId ->
            val serviceEntity = serviceRepository.findByIdAndStudioId(serviceId.value, command.studioId.value)
                ?: throw EntityNotFoundException("Usługa ${serviceId.value} nie została znaleziona")
            val item = ServicePackageItem(
                id = ServicePackageItemId.random(),
                packageId = packageService.id,
                serviceId = serviceId,
                serviceName = serviceEntity.name,
                studioId = command.studioId,
                position = index,
                createdAt = Instant.now()
            )
            ServicePackageItemEntity.fromDomain(item)
        }
        packageItemRepository.saveAll(itemEntities)

        auditService.log(LogAuditCommand(
            studioId = command.studioId,
            userId = command.userId,
            userDisplayName = command.userName ?: "",
            module = AuditModule.SERVICE,
            entityId = packageService.id.value.toString(),
            entityDisplayName = packageService.name,
            action = AuditAction.CREATE,
            changes = listOf(
                FieldChange("name", null, packageService.name),
                FieldChange("basePriceNet", null, netAmount.amountInCents.toString()),
                FieldChange("vatRate", null, command.vatRate.rate.toString()),
                FieldChange("requireManualPrice", null, packageService.requireManualPrice.toString()),
                FieldChange("isPackage", null, "true"),
                FieldChange("serviceIds", null, command.serviceIds.joinToString(",") { it.value.toString() })
            )
        ))

        CreateServiceResult(
            serviceId = packageService.id,
            name = packageService.name,
            basePriceNet = netAmount.amountInCents,
            vatRate = command.vatRate.rate,
            vatAmount = vatAmount.amountInCents,
            priceGross = grossAmount.amountInCents,
            requireManualPrice = packageService.requireManualPrice
        )
    }

    private fun validatePackageItems(command: CreatePackageCommand) {
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
