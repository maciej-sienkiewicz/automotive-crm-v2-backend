package pl.detailing.crm.finance.reporting

import org.springframework.stereotype.Service
import pl.detailing.crm.finance.domain.DocumentDirection
import pl.detailing.crm.finance.domain.DocumentStatus
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
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
 * All monetary values are in grosz (1/100 PLN).
 * "Revenue" = INCOME documents with status PAID.
 * "Costs"   = EXPENSE documents with status PAID.
 */
data class FinanceSummaryResult(

    /** Period covered by this report (mirrors query parameters). */
    val dateFrom: LocalDate?,
    val dateTo: LocalDate?,

    // ── Settled amounts ────────────────────────────────────────────────────
    val totalRevenue: Money,          // INCOME + PAID
    val totalCosts: Money,            // EXPENSE + PAID
    val profit: Money,                // revenue − costs (may produce Money(0) if costs > revenue)

    // ── Outstanding amounts ────────────────────────────────────────────────
    /** Sum of INCOME PENDING documents – money we expect to receive. */
    val pendingReceivables: Money,

    /** Sum of EXPENSE PENDING documents – money we still owe. */
    val pendingPayables: Money,

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
    private val documentRepository: FinancialDocumentRepository
) {
    fun getSummary(query: FinanceReportQuery): FinanceSummaryResult {
        val sid  = query.studioId.value
        val from = query.dateFrom
        val to   = query.dateTo

        val totalRevenueCents = documentRepository.sumGross(sid, DocumentDirection.INCOME,  DocumentStatus.PAID, from, to)
        val totalCostsCents   = documentRepository.sumGross(sid, DocumentDirection.EXPENSE, DocumentStatus.PAID, from, to)

        val pendingReceivablesCents = documentRepository.sumGross(sid, DocumentDirection.INCOME,  DocumentStatus.PENDING, from, to)
        val pendingPayablesCents    = documentRepository.sumGross(sid, DocumentDirection.EXPENSE, DocumentStatus.PENDING, from, to)

        val overdueReceivables = documentRepository.countOverdue(sid, DocumentDirection.INCOME)
        val overduePayables    = documentRepository.countOverdue(sid, DocumentDirection.EXPENSE)

        val profitCents = (totalRevenueCents - totalCostsCents).coerceAtLeast(0L)

        return FinanceSummaryResult(
            dateFrom             = from,
            dateTo               = to,
            totalRevenue         = Money(totalRevenueCents),
            totalCosts           = Money(totalCostsCents),
            profit               = Money(profitCents),
            pendingReceivables   = Money(pendingReceivablesCents),
            pendingPayables      = Money(pendingPayablesCents),
            overdueReceivables   = overdueReceivables,
            overduePayables      = overduePayables
        )
    }
}
