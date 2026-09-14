package pl.detailing.crm.finance.reporting

import org.springframework.stereotype.Service
import pl.detailing.crm.finance.domain.DocumentDirection
import pl.detailing.crm.finance.domain.DocumentStatus
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceRepository
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceRepository
import pl.detailing.crm.shared.Money
import pl.detailing.crm.shared.StudioId
import java.time.LocalDate

data class FinanceReportQuery(
    val studioId: StudioId,

    /**
     * Optional period filter.
     * When both are null, aggregates cover all time.
     * Typical use: pass first/last day of a month or year.
     */
    val dateFrom: LocalDate? = null,
    val dateTo: LocalDate? = null
)

/**
 * High-level financial summary for a studio.
 *
 * Wszystkie kwoty są w groszach (1/100 PLN) i **netto** — VAT nie jest pieniądzem
 * studia, tylko kwotą przechowywaną dla urzędu skarbowego. Kafle liczone brutto
 * zawyżały przychód o stawkę VAT, a zysk brutto minus koszty brutto porównywał
 * dwie kwoty, z których każda niosła cudzy podatek.
 *
 * "Revenue" = opłacone dokumenty przychodowe: paragony i dokumenty „inne"
 *             z modułu finansowego oraz faktury z ledgera KSeF. Korekty niosą
 *             kwoty ze znakiem, więc pomniejszają przychód.
 * "Costs"   = opłacone dokumenty kosztowe (moduł finansowy + faktury kosztowe KSeF).
 */
data class FinanceSummaryResult(

    /** Period covered by this report (mirrors query parameters). */
    val dateFrom: LocalDate?,
    val dateTo: LocalDate?,

    // ── Settled amounts (netto) ────────────────────────────────────────────
    // Grosze jako Long, nie Money: Money zabrania kwot ujemnych, a te sumy niosą
    // korekty ze znakiem. Korekta wystawiona w okresie bez faktur (np. do faktury
    // z poprzedniego roku) daje ujemny przychód — to prawdziwa informacja,
    // a nie błąd, i nie może wywracać całego widoku finansów wyjątkiem.
    val totalRevenue: Long,           // netto, INCOME + PAID (po korektach)
    val totalCosts: Long,             // netto, EXPENSE + PAID
    val profit: Long,                 // revenue − costs, obie strony netto

    // ── Outstanding amounts (netto) ────────────────────────────────────────
    /** Sum of INCOME PENDING documents – money we expect to receive (po korektach). */
    val pendingReceivables: Long,

    /** Sum of EXPENSE PENDING documents – money we still owe. */
    val pendingPayables: Long,

    // ── Overdue counts ─────────────────────────────────────────────────────
    /** Count of INCOME OVERDUE documents (invoices not paid by customer). */
    val overdueReceivables: Long,

    /** Count of EXPENSE OVERDUE documents (bills we haven't paid on time). */
    val overduePayables: Long
)

/**
 * Computes high-level financial metrics for a studio.
 *
 * All aggregation queries run within the caller's transaction context.
 * For large datasets, consider adding materialized views or a dedicated
 * reporting schema; for a CRM scale this approach is sufficient.
 */
@Service
class FinanceReportingHandler(
    private val documentRepository: FinancialDocumentRepository,
    private val ksefInvoiceRepository: KsefInvoiceRepository,
    private val revenueInvoiceRepository: KsefRevenueInvoiceRepository
) {
    companion object {
        /** Rozliczone: pieniądze faktycznie przeszły. */
        private val SETTLED = listOf(DocumentStatus.PAID)

        /**
         * Nierozliczone: pieniądze wciąż są do odebrania albo do zapłacenia.
         * OVERDUE to nadal należność — termin minął, ale dług nie zniknął.
         */
        private val OUTSTANDING = listOf(DocumentStatus.PENDING, DocumentStatus.OVERDUE)
    }

    fun getSummary(query: FinanceReportQuery): FinanceSummaryResult {
        val sid  = query.studioId.value
        val from = query.dateFrom
        val to   = query.dateTo

        // Przychód pochodzi z dwóch źródeł, tak samo jak lista dokumentów przychodowych:
        // paragony i dokumenty „inne" z modułu finansowego oraz faktury (i korekty)
        // z ledgera KSeF. Dokumenty finansowe powiązane z fakturą KSeF są po stronie
        // sumNet pomijane, więc nic nie liczy się dwa razy.
        //
        // Obie strony sumują kwoty netto: przychód, koszt i zysk mają mówić
        // o pieniądzach studia, a nie o VAT przechodzącym do urzędu skarbowego.
        val financialDocRevenueCents = documentRepository.sumNet(sid, DocumentDirection.INCOME, SETTLED, from, to)
        val ksefRevenueCents = revenueInvoiceRepository.sumNetByPaymentStatus(sid, "PAID", from, to)
        val totalRevenueCents = financialDocRevenueCents + ksefRevenueCents

        val financialDocCostsCents = documentRepository.sumNet(sid, DocumentDirection.EXPENSE, SETTLED, from, to)
        val ksefCostsCents = ksefInvoiceRepository.sumNetByPaymentStatus(sid, "PAID", from, to)
        val totalCostsCents = financialDocCostsCents + ksefCostsCents

        val financialDocReceivablesCents = documentRepository.sumNet(sid, DocumentDirection.INCOME, OUTSTANDING, from, to)
        val ksefReceivablesCents = revenueInvoiceRepository.sumNetByPaymentStatus(sid, "PENDING", from, to)
        val pendingReceivablesCents = financialDocReceivablesCents + ksefReceivablesCents

        val financialDocPendingPayablesCents = documentRepository.sumNet(sid, DocumentDirection.EXPENSE, OUTSTANDING, from, to)
        val ksefPendingPayablesCents = ksefInvoiceRepository.sumNetByPaymentStatus(sid, "PENDING", from, to)
        val pendingPayablesCents = financialDocPendingPayablesCents + ksefPendingPayablesCents

        val overdueReceivables = documentRepository.countOverdue(sid, DocumentDirection.INCOME)
        val overduePayables    = documentRepository.countOverdue(sid, DocumentDirection.EXPENSE)

        return FinanceSummaryResult(
            dateFrom             = from,
            dateTo               = to,
            totalRevenue         = totalRevenueCents,
            totalCosts           = totalCostsCents,
            // Strata jest pokazywana wprost — wcześniejsze przycięcie do zera
            // udawało wyjście na zero w miesiącu, w którym koszty przewyższyły przychód
            profit               = totalRevenueCents - totalCostsCents,
            pendingReceivables   = pendingReceivablesCents,
            pendingPayables      = pendingPayablesCents,
            overdueReceivables   = overdueReceivables,
            overduePayables      = overduePayables
        )
    }
}
