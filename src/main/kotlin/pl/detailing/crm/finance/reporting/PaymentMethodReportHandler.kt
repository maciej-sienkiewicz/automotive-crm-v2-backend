package pl.detailing.crm.finance.reporting

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.finance.domain.DocumentType
import pl.detailing.crm.finance.domain.PaymentMethod
import pl.detailing.crm.finance.infrastructure.FinancialDocumentEntity
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.shared.StudioId
import java.time.LocalDate

enum class ReportGranularity { MONTHLY, QUARTERLY, YEARLY }

data class PaymentMethodReportQuery(
    val studioId: StudioId,
    val granularity: ReportGranularity,
    val dateFrom: LocalDate?,
    val dateTo: LocalDate?,
    val documentType: DocumentType?
)

data class PaymentMethodStats(
    val count: Int,
    val totalNet: Long,
    val totalGross: Long
)

data class PeriodPaymentStats(
    val periodLabel: String,
    val dateFrom: LocalDate,
    val dateTo: LocalDate,
    val cash: PaymentMethodStats,
    val card: PaymentMethodStats,
    val transfer: PaymentMethodStats
)

data class PaymentMethodReportResult(
    val granularity: ReportGranularity,
    val dateFrom: LocalDate?,
    val dateTo: LocalDate?,
    val documentType: DocumentType?,
    val periods: List<PeriodPaymentStats>,
    val cash: PaymentMethodStats,
    val card: PaymentMethodStats,
    val transfer: PaymentMethodStats
)

@Service
class PaymentMethodReportHandler(
    private val documentRepository: FinancialDocumentRepository
) {

    @Transactional(readOnly = true)
    fun getReport(query: PaymentMethodReportQuery): PaymentMethodReportResult {
        val documents = documentRepository.findPaidIncomeForReport(
            studioId     = query.studioId.value,
            documentType = query.documentType?.name,
            dateFrom     = query.dateFrom,
            dateTo       = query.dateTo
        )

        val periods = generatePeriods(query.granularity, query.dateFrom, query.dateTo)
            .map { (label, from, to) ->
                val slice = documents.filter { !it.issueDate.isBefore(from) && !it.issueDate.isAfter(to) }
                PeriodPaymentStats(
                    periodLabel = label,
                    dateFrom    = from,
                    dateTo      = to,
                    cash        = statsFor(slice, PaymentMethod.CASH),
                    card        = statsFor(slice, PaymentMethod.CARD),
                    transfer    = statsFor(slice, PaymentMethod.TRANSFER)
                )
            }

        return PaymentMethodReportResult(
            granularity  = query.granularity,
            dateFrom     = query.dateFrom,
            dateTo       = query.dateTo,
            documentType = query.documentType,
            periods      = periods,
            cash         = statsFor(documents, PaymentMethod.CASH),
            card         = statsFor(documents, PaymentMethod.CARD),
            transfer     = statsFor(documents, PaymentMethod.TRANSFER)
        )
    }

    private fun statsFor(docs: List<FinancialDocumentEntity>, method: PaymentMethod): PaymentMethodStats {
        val transferMethods = setOf(PaymentMethod.TRANSFER, PaymentMethod.BLIK_NA_NUMER, PaymentMethod.BLIK_TERMINAL)
        val filtered = if (method == PaymentMethod.TRANSFER) {
            docs.filter { it.paymentMethod in transferMethods }
        } else {
            docs.filter { it.paymentMethod == method }
        }
        return PaymentMethodStats(
            count      = filtered.size,
            totalNet   = filtered.sumOf { it.totalNet },
            totalGross = filtered.sumOf { it.totalGross }
        )
    }

    private fun generatePeriods(
        granularity: ReportGranularity,
        from: LocalDate?,
        to: LocalDate?
    ): List<Triple<String, LocalDate, LocalDate>> {
        val effectiveFrom = from ?: LocalDate.now().withDayOfYear(1)
        val effectiveTo   = to   ?: LocalDate.now()
        return when (granularity) {
            ReportGranularity.MONTHLY   -> monthlyPeriods(effectiveFrom, effectiveTo)
            ReportGranularity.QUARTERLY -> quarterlyPeriods(effectiveFrom, effectiveTo)
            ReportGranularity.YEARLY    -> yearlyPeriods(effectiveFrom, effectiveTo)
        }
    }

    private fun monthlyPeriods(from: LocalDate, to: LocalDate): List<Triple<String, LocalDate, LocalDate>> {
        val result = mutableListOf<Triple<String, LocalDate, LocalDate>>()
        var cursor = from.withDayOfMonth(1)
        while (!cursor.isAfter(to)) {
            val end = minDate(cursor.plusMonths(1).minusDays(1), to)
            result += Triple("%d-%02d".format(cursor.year, cursor.monthValue), cursor, end)
            cursor = cursor.plusMonths(1)
        }
        return result
    }

    private fun quarterlyPeriods(from: LocalDate, to: LocalDate): List<Triple<String, LocalDate, LocalDate>> {
        val result = mutableListOf<Triple<String, LocalDate, LocalDate>>()
        var cursor = quarterStart(from)
        while (!cursor.isAfter(to)) {
            val end = minDate(cursor.plusMonths(3).minusDays(1), to)
            val q   = (cursor.monthValue - 1) / 3 + 1
            result += Triple("%d-Q%d".format(cursor.year, q), cursor, end)
            cursor = cursor.plusMonths(3)
        }
        return result
    }

    private fun yearlyPeriods(from: LocalDate, to: LocalDate): List<Triple<String, LocalDate, LocalDate>> {
        val result = mutableListOf<Triple<String, LocalDate, LocalDate>>()
        var cursor = from.withDayOfYear(1)
        while (!cursor.isAfter(to)) {
            val end = minDate(cursor.plusYears(1).minusDays(1), to)
            result += Triple(cursor.year.toString(), cursor, end)
            cursor = cursor.plusYears(1)
        }
        return result
    }

    private fun quarterStart(date: LocalDate): LocalDate {
        val firstMonthOfQuarter = (date.monthValue - 1) / 3 * 3 + 1
        return date.withMonth(firstMonthOfQuarter).withDayOfMonth(1)
    }

    private fun minDate(a: LocalDate, b: LocalDate): LocalDate = if (a.isBefore(b)) a else b
}
