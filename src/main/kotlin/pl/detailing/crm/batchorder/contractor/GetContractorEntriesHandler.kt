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

        val periodEntries = if (command.from != null && command.to != null) {
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

        // A settled entry is done with: it has been reported, invoiced and paid for, and
        // leaving it on the list buries the handful of entries that still need attention
        // under months of finished work. It is hidden by default and one checkbox away.
        val settledCount = periodEntries.count { it.isClosed }
        val entries = if (command.includeSettled) periodEntries else periodEntries.filter { !it.isClosed }

        val entryItems = entries.map { it.toEntryItem() }
        // Totals describe the list on screen, not the period: with settled entries hidden
        // the summary answers "what is left to settle", which is the question the screen
        // is being used to ask.
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
                entryCount = periodEntries.size.toLong(),
                createdAt = contractor.createdAt.toString(),
                updatedAt = contractor.updatedAt.toString()
            ),
            entries = entryItems,
            settledCount = settledCount,
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
    val to: LocalDate?,
    val includeSettled: Boolean = false
)

data class GetContractorEntriesResult(
    val contractor: ContractorListItem,
    val entries: List<EntryItem>,
    /** Settled entries in the period, counted whether or not they are in [entries]. */
    val settledCount: Int,
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
    val isClosed: Boolean,
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
    isClosed = isClosed,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString()
)
