package pl.detailing.crm.batchorder.contractor

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.batchorder.infrastructure.BatchContractorRepository
import pl.detailing.crm.batchorder.infrastructure.BatchOrderCloseHistoryRepository
import pl.detailing.crm.batchorder.infrastructure.BatchOrderEntryEntity
import pl.detailing.crm.batchorder.infrastructure.BatchOrderEntryRepository
import pl.detailing.crm.batchorder.infrastructure.BatchOrderPhotoRepository
import pl.detailing.crm.batchorder.infrastructure.photoCountsByEntryId
import pl.detailing.crm.shared.BatchContractorId
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import java.time.LocalDate

@Service
class GetContractorEntriesHandler(
    private val contractorRepository: BatchContractorRepository,
    private val entryRepository: BatchOrderEntryRepository,
    private val closeHistoryRepository: BatchOrderCloseHistoryRepository,
    private val photoRepository: BatchOrderPhotoRepository
) {
    @Transactional(readOnly = true)
    suspend fun handle(command: GetContractorEntriesCommand): GetContractorEntriesResult {
        val contractor = contractorRepository.findByIdAndStudioId(command.contractorId.value, command.studioId.value)
            ?: throw EntityNotFoundException("Contractor not found")

        val period = EntryPeriod.of(command.from, command.to)
        val periodEntries = if (period != null) {
            entryRepository.findByContractorIdAndStudioIdAndDateRange(
                contractorId = command.contractorId.value,
                studioId = command.studioId.value,
                from = period.from,
                to = period.to
            )
        } else {
            entryRepository.findByContractorIdAndStudioId(
                contractorId = command.contractorId.value,
                studioId = command.studioId.value
            )
        }

        // A settled entry is done with: it has been reported, invoiced and paid for, and
        // leaving it on the list buries the handful of entries that still need attention
        // under months of finished work. It is hidden by default (status OPEN) and one
        // filter away.
        val (settledEntries, openEntries) = periodEntries.partition { it.isClosed }
        // Newest first. The range query stays ASC on purpose — settlement and the PDF read
        // it in chronological order — so the list order is decided here, not in SQL.
        val entries = sortForList(periodEntries.filter { command.status.matches(it.isClosed) })

        val photoCounts = photoRepository.photoCountsByEntryId(command.studioId.value, entries.map { it.id })
        val stamps = closeHistoryRepository.findStampsByContractorIdAndStudioId(command.contractorId.value, command.studioId.value)

        return GetContractorEntriesResult(
            contractor = contractor.toListItem(entryCount = periodEntries.size.toLong()),
            entries = entries.map { it.toEntryItem(photoCount = photoCounts[it.id] ?: 0) },
            settledCount = settledEntries.size,
            // Totals describe the list on screen, not the period: with settled entries hidden
            // the summary answers "what is left to settle", which is the question the screen
            // is being used to ask. The two below answer the other questions regardless of
            // the filter, so the screen never has to reload to show both.
            summary = summarize(entries),
            openSummary = summarize(openEntries),
            settledSummary = summarize(settledEntries),
            lastSettledAt = lastSettledAt(stamps, period)?.toString()
        )
    }
}

data class GetContractorEntriesCommand(
    val studioId: StudioId,
    val contractorId: BatchContractorId,
    val from: LocalDate?,
    val to: LocalDate?,
    val status: EntryStatusFilter = EntryStatusFilter.OPEN
)

data class GetContractorEntriesResult(
    val contractor: ContractorListItem,
    val entries: List<EntryItem>,
    /** Settled entries in the period, counted whether or not they are in [entries]. */
    val settledCount: Int,
    /** Totals of [entries], i.e. of what the status filter let through. */
    val summary: EntrySummary,
    /** Open (not settled) entries in the period, whatever the filter. */
    val openSummary: EntrySummary,
    /** Settled entries in the period, whatever the filter. */
    val settledSummary: EntrySummary,
    /** ISO instant of the newest settlement overlapping the period (newest overall without a period). */
    val lastSettledAt: String?
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
    val updatedAt: String,
    val photoCount: Int,
    /** Settled once, then unlocked for correction; cleared when settled again. */
    val isCorrection: Boolean,
    val closeHistoryId: String?
)

data class EntrySummary(
    val totalNetCents: Long,
    val totalGrossCents: Long,
    val entryCount: Int
)

/**
 * [photoCount] comes from outside because photos live in their own table: the list
 * passes a grouped count, single-entry responses count that one entry.
 */
fun BatchOrderEntryEntity.toEntryItem(photoCount: Int = 0) = EntryItem(
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
    updatedAt = updatedAt.toString(),
    photoCount = photoCount,
    isCorrection = isCorrection,
    closeHistoryId = closeHistoryId?.toString()
)
