package pl.detailing.crm.ksef.statistics

import org.springframework.stereotype.Service
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceRepository
import pl.detailing.crm.shared.StudioId
import java.time.OffsetDateTime
import java.time.ZoneOffset

data class KsefStatisticsQuery(
    val studioId: StudioId,
    val year: Int
)

data class KsefMonthlyBreakdown(
    val monthLabel: String,       // format: "YYYY-MM"
    val revenueGross: Double,
    val revenueNet: Double,
    val revenueVat: Double,
    val costsGross: Double,
    val costsNet: Double,
    val costsVat: Double,
    val profitGross: Double,      // revenueGross - costsGross
    val profitNet: Double,        // revenueNet   - costsNet
    val incomeCount: Long,
    val expenseCount: Long,
    val correctionCount: Long
)

data class KsefStatisticsTotals(
    val revenueGross: Double,
    val revenueNet: Double,
    val revenueVat: Double,
    val costsGross: Double,
    val costsNet: Double,
    val costsVat: Double,
    val profitGross: Double,
    val profitNet: Double,
    val incomeCount: Long,
    val expenseCount: Long,
    val correctionCount: Long
)

data class KsefStatisticsResult(
    val year: Int,
    val totals: KsefStatisticsTotals,
    val monthly: List<KsefMonthlyBreakdown>,
    val syncWarning: String?       // null = dane aktualne; non-null = informacja o dacie ostatniego sync
)

@Service
class KsefStatisticsHandler(private val invoiceRepository: KsefInvoiceRepository) {

    fun handle(query: KsefStatisticsQuery): KsefStatisticsResult {
        val dateFrom = OffsetDateTime.of(query.year, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
        val dateTo   = OffsetDateTime.of(query.year + 1, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)

        val monthlyRows = invoiceRepository.findMonthlyStatistics(
            studioId = query.studioId.value,
            dateFrom = dateFrom,
            dateTo = dateTo
        )

        val totalRow = invoiceRepository.findTotalStatistics(
            studioId = query.studioId.value,
            dateFrom = dateFrom,
            dateTo = dateTo
        )

        val monthly = monthlyRows.map { row -> mapMonthly(row) }
        val totals  = mapTotals(totalRow)

        return KsefStatisticsResult(
            year = query.year,
            totals = totals,
            monthly = monthly,
            syncWarning = null
        )
    }

    // ─── Mapowanie wyników native query ──────────────────────────────────────

    /**
     * Kolejność kolumn z findMonthlyStatistics (indeks 0-9):
     * 0  month_label, 1 revenue_gross, 2 revenue_net, 3 revenue_vat,
     * 4 costs_gross,  5 costs_net,     6 costs_vat,
     * 7 income_count, 8 expense_count, 9 correction_count
     */
    private fun mapMonthly(row: Array<Any?>): KsefMonthlyBreakdown {
        val revenueGross = toDouble(row[1])
        val revenueNet   = toDouble(row[2])
        val revenueVat   = toDouble(row[3])
        val costsGross   = toDouble(row[4])
        val costsNet     = toDouble(row[5])
        val costsVat     = toDouble(row[6])
        return KsefMonthlyBreakdown(
            monthLabel      = row[0]?.toString() ?: "?",
            revenueGross    = revenueGross,
            revenueNet      = revenueNet,
            revenueVat      = revenueVat,
            costsGross      = costsGross,
            costsNet        = costsNet,
            costsVat        = costsVat,
            profitGross     = revenueGross - costsGross,
            profitNet       = revenueNet   - costsNet,
            incomeCount     = toLong(row[7]),
            expenseCount    = toLong(row[8]),
            correctionCount = toLong(row[9])
        )
    }

    /**
     * Kolejność kolumn z findTotalStatistics (indeks 0-8):
     * 0 revenue_gross, 1 revenue_net, 2 revenue_vat,
     * 3 costs_gross,   4 costs_net,   5 costs_vat,
     * 6 income_count,  7 expense_count, 8 correction_count
     */
    private fun mapTotals(row: Array<Any?>): KsefStatisticsTotals {
        val revenueGross = toDouble(row[0])
        val revenueNet   = toDouble(row[1])
        val revenueVat   = toDouble(row[2])
        val costsGross   = toDouble(row[3])
        val costsNet     = toDouble(row[4])
        val costsVat     = toDouble(row[5])
        return KsefStatisticsTotals(
            revenueGross    = revenueGross,
            revenueNet      = revenueNet,
            revenueVat      = revenueVat,
            costsGross      = costsGross,
            costsNet        = costsNet,
            costsVat        = costsVat,
            profitGross     = revenueGross - costsGross,
            profitNet       = revenueNet   - costsNet,
            incomeCount     = toLong(row[6]),
            expenseCount    = toLong(row[7]),
            correctionCount = toLong(row[8])
        )
    }

    private fun toDouble(value: Any?): Double = when (value) {
        null             -> 0.0
        is Double        -> value
        is Float         -> value.toDouble()
        is Number        -> value.toDouble()
        else             -> value.toString().toDoubleOrNull() ?: 0.0
    }

    private fun toLong(value: Any?): Long = when (value) {
        null      -> 0L
        is Long   -> value
        is Number -> value.toLong()
        else      -> value.toString().toLongOrNull() ?: 0L
    }
}
