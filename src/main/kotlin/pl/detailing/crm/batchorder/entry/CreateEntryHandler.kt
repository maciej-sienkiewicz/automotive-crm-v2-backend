package pl.detailing.crm.batchorder.entry

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.batchorder.contractor.EntryItem
import pl.detailing.crm.batchorder.domain.BatchOrderEntry
import pl.detailing.crm.batchorder.infrastructure.BatchContractorRepository
import pl.detailing.crm.batchorder.infrastructure.BatchOrderEntryEntity
import pl.detailing.crm.batchorder.infrastructure.BatchOrderEntryRepository
import pl.detailing.crm.shared.BatchContractorId
import pl.detailing.crm.shared.BatchOrderEntryId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.time.LocalDate

@Service
class CreateEntryHandler(
    private val entryRepository: BatchOrderEntryRepository,
    private val contractorRepository: BatchContractorRepository
) {
    @Transactional
    suspend fun handle(command: CreateEntryCommand): EntryItem {
        contractorRepository.findByIdAndStudioId(command.contractorId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Contractor not found")

        if (command.netAmountCents < 0) throw ValidationException("Net amount cannot be negative")
        if (command.grossAmountCents < 0) throw ValidationException("Gross amount cannot be negative")

        val entry = BatchOrderEntry(
            id = BatchOrderEntryId.random(),
            studioId = command.studioId,
            contractorId = command.contractorId,
            serviceDate = command.serviceDate,
            vehicleMake = command.vehicleMake?.trim()?.takeIf { it.isNotBlank() },
            vehicleModel = command.vehicleModel?.trim()?.takeIf { it.isNotBlank() },
            vehicleLicensePlate = command.vehicleLicensePlate?.trim()?.takeIf { it.isNotBlank() },
            services = command.services.filter { it.isNotBlank() }.map { it.trim() },
            netAmountCents = command.netAmountCents,
            grossAmountCents = command.grossAmountCents,
            vatRate = command.vatRate,
            notes = command.notes?.trim()?.takeIf { it.isNotBlank() },
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        val saved = entryRepository.save(BatchOrderEntryEntity.fromDomain(entry))
        return saved.toEntryItem()
    }
}

data class CreateEntryCommand(
    val studioId: StudioId,
    val contractorId: BatchContractorId,
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

fun BatchOrderEntryEntity.toEntryItem() = EntryItem(
    id = id.toString(),
    serviceDate = serviceDate.toString(),
    vehicleMake = vehicleMake,
    vehicleModel = vehicleModel,
    vehicleLicensePlate = vehicleLicensePlate,
    services = services.toList(),
    netAmountCents = netAmountCents,
    grossAmountCents = grossAmountCents,
    vatRate = vatRate,
    notes = notes,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString()
)
