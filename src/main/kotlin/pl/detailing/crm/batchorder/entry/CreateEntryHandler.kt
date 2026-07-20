package pl.detailing.crm.batchorder.entry

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.batchorder.contractor.EntryItem
import pl.detailing.crm.batchorder.contractor.toEntryItem
import pl.detailing.crm.batchorder.domain.BatchOrderEntry
import pl.detailing.crm.batchorder.domain.BatchOrderServiceItem
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

        command.services.forEach { item ->
            if (item.netAmountCents < 0) throw ValidationException("Net amount cannot be negative")
            if (item.grossAmountCents < 0) throw ValidationException("Gross amount cannot be negative")
        }

        val entry = BatchOrderEntry(
            id = BatchOrderEntryId.random(),
            studioId = command.studioId,
            contractorId = command.contractorId,
            serviceDate = command.serviceDate,
            vehicleMake = command.vehicleMake?.trim()?.takeIf { it.isNotBlank() },
            vehicleModel = command.vehicleModel?.trim()?.takeIf { it.isNotBlank() },
            vehicleLicensePlate = command.vehicleLicensePlate?.trim()?.takeIf { it.isNotBlank() },
            services = command.services
                .filter { it.name.isNotBlank() }
                .map { BatchOrderServiceItem(it.name.trim(), it.netAmountCents, it.grossAmountCents, it.vatRate) },
            notes = command.notes?.trim()?.takeIf { it.isNotBlank() },
            createdAt = Instant.now(),
            updatedAt = Instant.now()
        )

        val saved = entryRepository.save(BatchOrderEntryEntity.fromDomain(entry))
        return saved.toEntryItem()
    }
}

data class ServiceItemInput(
    val name: String,
    val netAmountCents: Long,
    val grossAmountCents: Long,
    val vatRate: Int
)

data class CreateEntryCommand(
    val studioId: StudioId,
    val contractorId: BatchContractorId,
    val serviceDate: LocalDate,
    val vehicleMake: String?,
    val vehicleModel: String?,
    val vehicleLicensePlate: String?,
    val services: List<ServiceItemInput>,
    val notes: String?
)
