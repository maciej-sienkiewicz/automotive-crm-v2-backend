package pl.detailing.crm.batchorder.entry

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.batchorder.contractor.EntryItem
import pl.detailing.crm.batchorder.infrastructure.BatchOrderEntryRepository
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
        if (command.netAmountCents < 0) throw ValidationException("Net amount cannot be negative")
        if (command.grossAmountCents < 0) throw ValidationException("Gross amount cannot be negative")

        val entity = entryRepository.findByIdAndStudioId(command.entryId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Entry not found")

        entity.serviceDate = command.serviceDate
        entity.vehicleMake = command.vehicleMake?.trim()?.takeIf { it.isNotBlank() }
        entity.vehicleModel = command.vehicleModel?.trim()?.takeIf { it.isNotBlank() }
        entity.vehicleLicensePlate = command.vehicleLicensePlate?.trim()?.takeIf { it.isNotBlank() }
        entity.services = command.services.filter { it.isNotBlank() }.map { it.trim() }.toMutableList()
        entity.netAmountCents = command.netAmountCents
        entity.grossAmountCents = command.grossAmountCents
        entity.vatRate = command.vatRate
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
    val services: List<String>,
    val netAmountCents: Long,
    val grossAmountCents: Long,
    val vatRate: Int,
    val notes: String?
)
