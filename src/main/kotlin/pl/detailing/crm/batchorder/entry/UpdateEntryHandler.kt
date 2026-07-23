package pl.detailing.crm.batchorder.entry

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.batchorder.contractor.EntryItem
import pl.detailing.crm.batchorder.contractor.toEntryItem
import pl.detailing.crm.batchorder.infrastructure.BatchOrderEntryRepository
import pl.detailing.crm.batchorder.infrastructure.ServiceItemEmbeddable
import pl.detailing.crm.shared.BatchOrderEntryId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.time.LocalDate

@Service
class UpdateEntryHandler(
    private val entryRepository: BatchOrderEntryRepository
) {
    @Transactional
    suspend fun handle(command: UpdateEntryCommand): EntryItem {
        command.services.forEach { item ->
            if (item.netAmountCents < 0) throw ValidationException("Net amount cannot be negative")
            if (item.grossAmountCents < 0) throw ValidationException("Gross amount cannot be negative")
        }

        val entity = entryRepository.findByIdAndStudioId(command.entryId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Entry not found")

        entity.serviceDate = command.serviceDate
        entity.vehicleMake = command.vehicleMake?.trim()?.takeIf { it.isNotBlank() }
        entity.vehicleModel = command.vehicleModel?.trim()?.takeIf { it.isNotBlank() }
        entity.vehicleLicensePlate = command.vehicleLicensePlate?.trim()?.takeIf { it.isNotBlank() }
        entity.vehicleVin = command.vehicleVin?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        entity.services = command.services
            .filter { it.name.isNotBlank() }
            .map { ServiceItemEmbeddable(it.name.trim(), it.netAmountCents, it.grossAmountCents, it.vatRate) }
            .toMutableList()
        entity.notes = command.notes?.trim()?.takeIf { it.isNotBlank() }
        entity.updatedAt = Instant.now()

        val saved = entryRepository.save(entity)
        return saved.toEntryItem()
    }
}

data class UpdateEntryCommand(
    val studioId: StudioId,
    val entryId: BatchOrderEntryId,
    val serviceDate: LocalDate,
    val vehicleMake: String?,
    val vehicleModel: String?,
    val vehicleLicensePlate: String?,
    val vehicleVin: String?,
    val services: List<ServiceItemInput>,
    val notes: String?
)
