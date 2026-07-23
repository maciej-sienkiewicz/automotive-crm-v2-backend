package pl.detailing.crm.batchorder.contractor

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.batchorder.infrastructure.BatchContractorRepository
import pl.detailing.crm.batchorder.infrastructure.BatchOrderEntryEntity
import pl.detailing.crm.batchorder.infrastructure.BatchOrderEntryRepository
import pl.detailing.crm.shared.BatchContractorId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import java.time.LocalDate

@Service
class GetContractorEntriesHandler(
    private val contractorRepository: BatchContractorRepository,
    private val entryRepository: BatchOrderEntryRepository
) {
    @Transactional(readOnly = true)
    suspend fun handle(command: GetContractorEntriesCommand): GetContractorEntriesResult {
        val contractor = contractorRepository.findByIdAndStudioId(command.contractorId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Contractor not found")

        val entries = if (command.from != null && command.to != null) {
            entryRepository.findByContractorIdAndStudioIdAndDateRange(
                contractorId = command.contractorId.value,
                studioId = command.studioId.value,
                from = command.from,
                to = command.to
            )
        } else {
            entryRepository.findByContractorIdAndStudioId(
                contractorId = command.contractorId.value,
                studioId = command.studioId.value
            )
        }

        val entryItems = entries.map { it.toEntryItem() }
        val totalNetCents = entries.sumOf { it.netAmountCents }
        val totalGrossCents = entries.sumOf { it.grossAmountCents }

        return GetContractorEntriesResult(
            contractor = ContractorListItem(
                id = contractor.id.toString(),
                name = contractor.name,
                taxId = contractor.taxId,
                address = contractor.address,
                contactPersonName = contractor.contactPersonName,
                email = contractor.email,
                phone = contractor.phone,
                notes = contractor.notes,
                isActive = contractor.isActive,
                entryCount = entries.size.toLong(),
                createdAt = contractor.createdAt.toString(),
                updatedAt = contractor.updatedAt.toString()
            ),
            entries = entryItems,
            summary = EntrySummary(
                totalNetCents = totalNetCents,
                totalGrossCents = totalGrossCents,
                entryCount = entries.size
            )
        )
    }
}

data class GetContractorEntriesCommand(
    val studioId: StudioId,
    val contractorId: BatchContractorId,
    val from: LocalDate?,
    val to: LocalDate?
)

data class GetContractorEntriesResult(
    val contractor: ContractorListItem,
    val entries: List<EntryItem>,
    val summary: EntrySummary
)

data class ServiceItemDto(
    val name: String,
    val netAmountCents: Long,
    val grossAmountCents: Long,
    val vatRate: Int
)

data class EntryItem(
    val id: String,
    val serviceDate: String,
    val vehicleMake: String?,
    val vehicleModel: String?,
    val vehicleLicensePlate: String?,
    val vehicleVin: String?,
    val services: List<ServiceItemDto>,
    val netAmountCents: Long,
    val grossAmountCents: Long,
    val notes: String?,
    val createdAt: String,
    val updatedAt: String
)

data class EntrySummary(
    val totalNetCents: Long,
    val totalGrossCents: Long,
    val entryCount: Int
)

fun BatchOrderEntryEntity.toEntryItem() = EntryItem(
    id = id.toString(),
    serviceDate = serviceDate.toString(),
    vehicleMake = vehicleMake,
    vehicleModel = vehicleModel,
    vehicleLicensePlate = vehicleLicensePlate,
    vehicleVin = vehicleVin,
    services = services.map { ServiceItemDto(it.name, it.netAmountCents, it.grossAmountCents, it.vatRate) },
    netAmountCents = netAmountCents,
    grossAmountCents = grossAmountCents,
    notes = notes,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString()
)
