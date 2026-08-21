package pl.detailing.crm.ksef.statistics

import org.springframework.stereotype.Service
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceRepository
import pl.detailing.crm.shared.StudioId
import java.time.OffsetDateTime
import java.time.ZoneOffset

data class KsefStatisticsQuery(val studioId: StudioId, val year: Int)

/** Kwoty w ZŁOTYCH — baza trzyma grosze, kontrakt HTTP pozostaje w złotych. */
data class KsefMonthlyExpense(
    val month: String,
    val costsGross: Double,
    val costsNet: Double,
    val costsVat: Double,
    val expenseCount: Long,
    val correctionCount: Long
)

data class KsefExpenseTotals(
    val costsGross: Double,
    val costsNet: Double,
    val costsVat: Double,
    val expenseCount: Long,
    val correctionCount: Long
)

data class KsefStatisticsResult(
    val year: Int,
    val totals: KsefExpenseTotals,
    val monthly: List<KsefMonthlyExpense>
)

@Service
class KsefStatisticsHandler(private val invoiceRepository: KsefInvoiceRepository) {

    fun handle(query: KsefStatisticsQuery): KsefStatisticsResult {
        val dateFrom = OffsetDateTime.of(query.year, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        val dateTo   = OffsetDateTime.of(query.year + 1, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)

        val monthlyRows = invoiceRepository.findMonthlyStatistics(query.studioId.value, dateFrom, dateTo)
        val totalRow    = invoiceRepository.findTotalStatistics(query.studioId.value, dateFrom, dateTo)

        return KsefStatisticsResult(
            year    = query.year,
            totals  = mapTotals(totalRow),
            monthly = monthlyRows.map { mapMonthly(it) }
        )
    }

    // month_label, costs_gross, costs_net, costs_vat, expense_count, correction_count
    private fun mapMonthly(row: Array<Any?>) = KsefMonthlyExpense(
        month           = row[0]?.toString() ?: "?",
        costsGross      = groszToZloty(row[1]),
        costsNet        = groszToZloty(row[2]),
        costsVat        = groszToZloty(row[3]),
        expenseCount    = toLong(row[4]),
        correctionCount = toLong(row[5])
    )

    // costs_gross, costs_net, costs_vat, expense_count, correction_count
    private fun mapTotals(row: Array<Any?>) = KsefExpenseTotals(
        costsGross      = groszToZloty(row[0]),
        costsNet        = groszToZloty(row[1]),
        costsVat        = groszToZloty(row[2]),
        expenseCount    = toLong(row[3]),
        correctionCount = toLong(row[4])
    )

    /**
     * Sumy z bazy przychodzą w groszach; kontrakt tego endpointu jest w złotych,
     * więc dzielimy dokładnie tutaj — na granicy HTTP, a nie w arytmetyce.
     */
    private fun groszToZloty(v: Any?): Double = toLong(v) / 100.0

    private fun toLong(v: Any?): Long = when (v) {
        null     -> 0L
        is Long  -> v
        is Number -> v.toLong()
        else     -> v.toString().toLongOrNull() ?: 0L
    }
}
