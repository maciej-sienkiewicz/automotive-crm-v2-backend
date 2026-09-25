package pl.detailing.crm.batchorder.contractor

import pl.detailing.crm.batchorder.infrastructure.BatchOrderEntryEntity
import pl.detailing.crm.batchorder.infrastructure.SettlementStamp
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.time.LocalDate

/**
 * Które wpisy pokazać: otwarte (do rozliczenia), rozliczone albo wszystkie.
 * Ten sam filtr stosuje lista i PDF, żeby suma na wydruku była sumą z ekranu.
 */
enum class EntryStatusFilter {
    OPEN, SETTLED, ALL;

    fun matches(isClosed: Boolean): Boolean = when (this) {
        OPEN -> !isClosed
        SETTLED -> isClosed
        ALL -> true
    }

    companion object {
        /** [status] z zapytania; brak (albo pusty) → [default]. Nieznana wartość to błąd, nie cichy fallback. */
        fun resolve(status: String?, default: EntryStatusFilter): EntryStatusFilter {
            val normalized = status?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return default
            return entries.firstOrNull { it.name == normalized }
                ?: throw ValidationException("Nieznany status wpisów: '$status'. Dozwolone: OPEN, SETTLED, ALL.")
        }

        /**
         * Lista wpisów: `status` ma pierwszeństwo, a starsze `includeSettled=true` znaczy ALL.
         * Domyślnie OPEN — rozliczone wpisy zasypywałyby te, które jeszcze czekają.
         */
        fun forEntryList(status: String?, includeSettled: Boolean): EntryStatusFilter =
            resolve(status, if (includeSettled) ALL else OPEN)
    }
}

/** Okres listy — oba końce albo żaden, tak jak zapytanie o wpisy. */
data class EntryPeriod(val from: LocalDate, val to: LocalDate) {
    fun overlaps(fromDate: LocalDate, toDate: LocalDate): Boolean = !fromDate.isAfter(to) && !toDate.isBefore(from)

    companion object {
        fun of(from: LocalDate?, to: LocalDate?): EntryPeriod? =
            if (from != null && to != null) EntryPeriod(from, to) else null
    }
}

fun summarize(entries: Collection<BatchOrderEntryEntity>) = EntrySummary(
    totalNetCents = entries.sumOf { it.netAmountCents },
    totalGrossCents = entries.sumOf { it.grossAmountCents },
    entryCount = entries.size
)

/** Najnowsze na górze: ostatnio wykonana praca, a przy tej samej dacie ostatnio dopisany wpis. */
fun sortForList(entries: List<BatchOrderEntryEntity>): List<BatchOrderEntryEntity> =
    entries.sortedWith(compareByDescending<BatchOrderEntryEntity> { it.serviceDate }.thenByDescending { it.createdAt })

/**
 * Kiedy ostatnio rozliczono ten okres: najnowsze rozliczenie, którego okres zachodzi na
 * [period]; bez okresu — najnowsze w ogóle. Kolejność [stamps] nie ma znaczenia.
 */
fun lastSettledAt(stamps: Collection<SettlementStamp>, period: EntryPeriod?): Instant? =
    stamps.asSequence()
        .filter { period == null || period.overlaps(it.fromDate, it.toDate) }
        .maxOfOrNull { it.closedAt }
