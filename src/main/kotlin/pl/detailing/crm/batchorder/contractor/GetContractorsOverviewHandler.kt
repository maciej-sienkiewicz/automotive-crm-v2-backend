package pl.detailing.crm.batchorder.contractor

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.batchorder.infrastructure.BatchContractorEntity
import pl.detailing.crm.batchorder.infrastructure.BatchContractorRepository
import pl.detailing.crm.batchorder.infrastructure.BatchOrderCloseHistoryRepository
import pl.detailing.crm.batchorder.infrastructure.BatchOrderEntryEntity
import pl.detailing.crm.batchorder.infrastructure.BatchOrderEntryRepository
import pl.detailing.crm.batchorder.infrastructure.SettlementStamp
import pl.detailing.crm.batchorder.infrastructure.entryCountsByContractorId
import pl.detailing.crm.shared.StudioId
import java.text.Collator
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

/**
 * Przegląd kontrahentów: ile u kogo czeka na rozliczenie w okresie i kiedy ostatnio
 * rozliczono. Stała liczba zapytań niezależnie od liczby kontrahentów — kontrahenci,
 * wpisy okresu (z usługami), liczniki wpisów i daty rozliczeń; reszta dzieje się
 * w pamięci, w [buildContractorsOverview].
 */
@Service
class GetContractorsOverviewHandler(
    private val contractorRepository: BatchContractorRepository,
    private val entryRepository: BatchOrderEntryRepository,
    private val closeHistoryRepository: BatchOrderCloseHistoryRepository
) {
    @Transactional(readOnly = true)
    suspend fun handle(command: GetContractorsOverviewCommand): List<ContractorOverviewItem> {
        val studioId = command.studioId.value
        val period = EntryPeriod.of(command.from, command.to)

        val contractors = contractorRepository.findActiveByStudioId(studioId)
        val entries = if (period != null) {
            entryRepository.findByStudioIdAndDateRangeWithServices(studioId, period.from, period.to)
        } else {
            entryRepository.findByStudioIdWithServices(studioId)
        }

        return buildContractorsOverview(
            contractors = contractors,
            entries = entries,
            stamps = closeHistoryRepository.findByStudioId(studioId),
            entryCounts = entryRepository.entryCountsByContractorId(studioId),
            period = period
        )
    }
}

/**
 * Kolejność: najwięcej brutto do rozliczenia na górze — przegląd służy do wybrania,
 * komu wystawić zestawienie — a przy remisie (zwykle: zero) alfabetycznie po polsku.
 *
 * [entries] mogą należeć do kontrahentów spoza [contractors] (nieaktywnych); są pomijane.
 */
fun buildContractorsOverview(
    contractors: List<BatchContractorEntity>,
    entries: List<BatchOrderEntryEntity>,
    stamps: List<SettlementStamp>,
    entryCounts: Map<UUID, Long>,
    period: EntryPeriod?
): List<ContractorOverviewItem> {
    val entriesByContractor = entries.groupBy { it.contractorId }
    val stampsByContractor = stamps.groupBy { it.contractorId }
    // Collator nie gwarantuje bezpieczeństwa wątkowego — tani, więc własny na wywołanie.
    val polishCollator = Collator.getInstance(Locale.forLanguageTag("pl-PL"))

    return contractors
        .map { contractor ->
            val own = entriesByContractor[contractor.id].orEmpty()
            val (settled, open) = own.partition { it.isClosed }
            val openSummary = summarize(open)
            val settledSummary = summarize(settled)
            ContractorOverviewItem(
                contractor = contractor.toListItem(entryCount = entryCounts[contractor.id] ?: 0L),
                openCount = openSummary.entryCount,
                openNetCents = openSummary.totalNetCents,
                openGrossCents = openSummary.totalGrossCents,
                settledCount = settled.size,
                settledNetCents = settledSummary.totalNetCents,
                settledGrossCents = settledSummary.totalGrossCents,
                lastSettledAt = lastSettledAt(stampsByContractor[contractor.id].orEmpty(), period)?.toString()
            )
        }
        .sortedWith(
            compareByDescending<ContractorOverviewItem> { it.openGrossCents }
                .thenComparator { a, b -> polishCollator.compare(a.contractor.name, b.contractor.name) }
        )
}

data class GetContractorsOverviewCommand(
    val studioId: StudioId,
    val from: LocalDate?,
    val to: LocalDate?
)

data class ContractorOverviewItem(
    val contractor: ContractorListItem,
    val openCount: Int,
    val openNetCents: Long,
    val openGrossCents: Long,
    val settledCount: Int,
    /**
     * Sumy wpisów okresu już ujętych w zestawieniach. Nagłówek listy mówi, na ile
     * wykonano usług w okresie — bez nich pokazywałby tylko to, co jeszcze nie
     * rozliczone, i kwota malała po każdym zestawieniu.
     */
    val settledNetCents: Long,
    val settledGrossCents: Long,
    val lastSettledAt: String?
)
