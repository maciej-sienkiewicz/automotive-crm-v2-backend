// src/main/kotlin/pl/detailing/crm/service/update/UpdateServiceHandler.kt

package pl.detailing.crm.service.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.service.domain.Service as ServiceDomain
import pl.detailing.crm.service.infrastructure.ServiceEntity
import pl.detailing.crm.service.infrastructure.ServicePackageItemRepository
import pl.detailing.crm.service.infrastructure.ServiceRepository
import pl.detailing.crm.shared.*
import java.time.Instant

@Service
class UpdateServiceHandler(
    private val validatorComposite: UpdateServiceValidatorComposite,
    private val serviceRepository: ServiceRepository,
    private val packageItemRepository: ServicePackageItemRepository,
    private val auditService: AuditService
) {

    @Transactional
    suspend fun handle(command: UpdateServiceCommand): UpdateServiceResult = withContext(Dispatchers.IO) {
        validatorComposite.validate(command)

        val oldServiceEntity = serviceRepository.findByIdAndStudioId(
            command.oldServiceId.value,
            command.studioId.value
        ) ?: throw EntityNotFoundException("Usługa nie została znaleziona")

        // Manual-price services must not carry a catalog price — any price sent by the client is dropped
        val netAmount = if (command.requireManualPrice) Money.ZERO else command.basePriceNet
        val grossAmount = if (command.requireManualPrice) Money.ZERO
            else command.vatRate.resolveGrossAmount(netAmount, command.basePriceGross)
        val vatAmount = grossAmount.minus(netAmount)
        val newName = command.name.trim()

        /*
         * Zapis bez zmian nie jest zmianą. Edytor pozycji na rezerwacji i na karcie
         * wizyty woła ten endpoint przy każdym potwierdzeniu okna — także wtedy, gdy
         * użytkownik tylko je otworzył i zamknął przyciskiem „Zapisz". Bez tego
         * warunku każde takie kliknięcie zakładało nową wersję usługi (stara szła w
         * isActive = false) i dokładało do Aktywności wiersz „Zaktualizowano usługę"
         * bez ani jednej zmiany w środku — nie do odróżnienia od wiersza sprzed
         * chwili, który zmiany niósł. Feed pokazywał duplikat, katalog puchł o wersje
         * bez treści, a referencje pozycji przeskakiwały na nowe id bez powodu.
         */
        if (oldServiceEntity.isActive && oldServiceEntity.isUnchangedBy(newName, netAmount, grossAmount, command)) {
            return@withContext UpdateServiceResult(
                oldServiceId = command.oldServiceId,
                newServiceId = command.oldServiceId,
                name = oldServiceEntity.name,
                basePriceNet = oldServiceEntity.basePriceNet,
                vatRate = oldServiceEntity.vatRate,
                vatAmount = oldServiceEntity.basePriceGross - oldServiceEntity.basePriceNet,
                priceGross = oldServiceEntity.basePriceGross,
                requireManualPrice = oldServiceEntity.requireManualPrice,
                replacesServiceId = command.oldServiceId,
                affectedPackages = affectedPackagesOf(command)
            )
        }

        oldServiceEntity.isActive = false
        oldServiceEntity.updatedAt = Instant.now()
        serviceRepository.save(oldServiceEntity)

        val newService = ServiceDomain(
            id = ServiceId.random(),
            studioId = command.studioId,
            name = newName,
            basePriceNet = netAmount,
            basePriceGross = grossAmount,
            vatRate = command.vatRate,
            isActive = true,
            requireManualPrice = command.requireManualPrice,
            isPackage = false,
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
            "basePriceNet" to auditMoney(oldServiceEntity.basePriceNet),
            "vatRate" to oldServiceEntity.vatRate.toString(),
            "requireManualPrice" to oldServiceEntity.requireManualPrice.toString()
        )
        val newValues = mapOf(
            "name" to newService.name,
            "basePriceNet" to auditMoney(netAmount.amountInCents),
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

        val affectedPackages = affectedPackagesOf(command)

        UpdateServiceResult(
            oldServiceId = command.oldServiceId,
            newServiceId = newService.id,
            name = newService.name,
            basePriceNet = netAmount.amountInCents,
            vatRate = command.vatRate.rate,
            vatAmount = vatAmount.amountInCents,
            priceGross = grossAmount.amountInCents,
            requireManualPrice = newService.requireManualPrice,
            replacesServiceId = command.oldServiceId,
            affectedPackages = affectedPackages
        )
    }

    /** Pakiety, w których siedzi zmieniana usługa — potrzebne przy każdym wyniku, także pustym. */
    private fun affectedPackagesOf(command: UpdateServiceCommand): List<AffectedPackage> {
        val affectedPackageItems = packageItemRepository.findByServiceIdAndStudioId(
            command.oldServiceId.value,
            command.studioId.value
        )
        if (affectedPackageItems.isEmpty()) return emptyList()

        val packageIds = affectedPackageItems.map { it.packageId }.distinct()
        return serviceRepository.findAllById(packageIds)
            .filter { it.isActive }
            .map { AffectedPackage(packageId = it.id.toString(), packageName = it.name) }
    }

    /**
     * Czy żądanie w ogóle coś zmienia. Porównywane jest dokładnie to, co usługa niesie
     * w katalogu i co trafia do dziennika zmian — nazwa, cena netto, cena brutto,
     * stawka VAT i tryb ceny ustalanej ręcznie.
     */
    private fun ServiceEntity.isUnchangedBy(
        newName: String,
        netAmount: Money,
        grossAmount: Money,
        command: UpdateServiceCommand
    ): Boolean =
        name == newName &&
            basePriceNet == netAmount.amountInCents &&
            basePriceGross == grossAmount.amountInCents &&
            vatRate == command.vatRate.rate &&
            requireManualPrice == command.requireManualPrice
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
    val replacesServiceId: ServiceId,
    val affectedPackages: List<AffectedPackage> = emptyList()
)

data class AffectedPackage(
    val packageId: String,
    val packageName: String
)