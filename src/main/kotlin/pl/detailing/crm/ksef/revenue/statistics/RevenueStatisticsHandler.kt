package pl.detailing.crm.ksef.revenue.statistics

import org.springframework.stereotype.Service
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceRepository
import pl.detailing.crm.shared.StudioId
import java.math.BigDecimal
import java.time.LocalDate

data class RevenueStatisticsQuery(val studioId: StudioId, val year: Int)

data class RevenueTotals(
    val gross: Long,
    val net: Long,
    val vat: Long,
    val invoiceCount: Long,
    val correctionCount: Long,
    val externalCount: Long
)

data class MonthlyRevenue(
    val month: String,
    val gross: Long,
    val net: Long,
    val vat: Long,
    val invoiceCount: Long,
    val correctionCount: Long,
    val externalCount: Long
)

data class RevenueStatisticsResult(
    val year: Int,
    val totals: RevenueTotals,
    val monthly: List<MonthlyRevenue>
)

/**
 * Statystyki przychodowe z jednego ledgera (CRM + EXTERNAL) — kompletne niezależnie
 * od tego, gdzie klient wystawił fakturę. Kwoty w groszach. Wyklucza faktury
 * odrzucone przez KSeF, potwierdzone duplikaty i faktury ukryte ręcznie ze statystyk;
 * korekty niosą znak, więc sumy odzwierciedlają rzeczywisty przychód po korektach.
 */
@Service
class RevenueStatisticsHandler(
    private val repository: KsefRevenueInvoiceRepository
) {

    fun handle(query: RevenueStatisticsQuery): RevenueStatisticsResult {
        val from = LocalDate.of(query.year, 1, 1)
        val to = LocalDate.of(query.year + 1, 1, 1)

        val totalsRow = repository.findTotalStatistics(query.studioId.value, from, to)
        val monthlyRows = repository.findMonthlyStatistics(query.studioId.value, from, to)

        return RevenueStatisticsResult(
            year = query.year,
            totals = RevenueTotals(
                gross = long(totalsRow[0]),
                net = long(totalsRow[1]),
                vat = long(totalsRow[2]),
                invoiceCount = long(totalsRow[3]),
                correctionCount = long(totalsRow[4]),
                externalCount = long(totalsRow[5])
            ),
            monthly = monthlyRows.map { row ->
                MonthlyRevenue(
                    month = row[0] as String,
                    gross = long(row[1]),
                    net = long(row[2]),
                    vat = long(row[3]),
                    invoiceCount = long(row[4]),
                    correctionCount = long(row[5]),
                    externalCount = long(row[6])
                )
            }
        )
    }

    private fun long(value: Any?): Long = when (value) {
        null -> 0L
        is Long -> value
        is Number -> value.toLong()
        is BigDecimal -> value.toLong()
        else -> 0L
    }
}
