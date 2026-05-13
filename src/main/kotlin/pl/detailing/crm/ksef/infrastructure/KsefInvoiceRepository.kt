package pl.detailing.crm.ksef.infrastructure

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime
import java.util.UUID

@Repository
interface KsefInvoiceRepository : JpaRepository<KsefInvoiceEntity, UUID> {

    fun findByIdAndStudioId(id: UUID, studioId: UUID): KsefInvoiceEntity?

    fun existsByStudioIdAndKsefNumber(studioId: UUID, ksefNumber: String): Boolean

    fun findByStudioIdAndKsefNumber(studioId: UUID, ksefNumber: String): KsefInvoiceEntity?

    /**
     * Paginated listing with optional source and paymentStatus filters.
     * EXCLUDED invoices are hidden by default (pass includeExcluded=true to show them).
     */
    @Query("""
        SELECT i FROM KsefInvoiceEntity i
        WHERE i.studioId = :studioId
          AND (:includeExcluded = true OR i.status <> 'EXCLUDED')
          AND (:source IS NULL OR i.source = :source)
          AND (:paymentStatus IS NULL OR i.paymentStatus = :paymentStatus)
          AND (:dateFrom IS NULL OR i.invoicingDate >= :dateFrom)
          AND (:dateTo   IS NULL OR i.invoicingDate <= :dateTo)
        ORDER BY i.invoicingDate DESC NULLS LAST, i.fetchedAt DESC
    """)
    fun findWithFilters(
        @Param("studioId") studioId: UUID,
        @Param("source") source: String?,
        @Param("paymentStatus") paymentStatus: String?,
        @Param("includeExcluded") includeExcluded: Boolean,
        @Param("dateFrom") dateFrom: OffsetDateTime?,
        @Param("dateTo") dateTo: OffsetDateTime?,
        pageable: Pageable
    ): Page<KsefInvoiceEntity>

    @Modifying
    @Query("UPDATE KsefInvoiceEntity i SET i.status = :status WHERE i.studioId = :studioId AND i.ksefNumber = :ksefNumber")
    fun updateStatus(
        @Param("studioId") studioId: UUID,
        @Param("ksefNumber") ksefNumber: String,
        @Param("status") status: String
    ): Int

    @Modifying
    @Query("UPDATE KsefInvoiceEntity i SET i.paymentStatus = :paymentStatus WHERE i.id = :id AND i.studioId = :studioId")
    fun updatePaymentStatus(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID,
        @Param("paymentStatus") paymentStatus: String
    ): Int

    // ── Statistics (native SQL for performance) ───────────────────────────────

    /**
     * Monthly expense breakdown, excluding CANCELLED and EXCLUDED invoices.
     * Correction invoices (FA_KOR) are included — their amounts carry the correct sign from KSeF.
     *
     * Result columns (index):
     * 0  month_label      – 'YYYY-MM'
     * 1  costs_gross
     * 2  costs_net
     * 3  costs_vat
     * 4  expense_count    – non-correction invoices
     * 5  correction_count
     */
    @Query(value = """
        SELECT
            TO_CHAR(DATE_TRUNC('month', invoicing_date), 'YYYY-MM')            AS month_label,
            COALESCE(SUM(gross_amount), 0)                                      AS costs_gross,
            COALESCE(SUM(net_amount),   0)                                      AS costs_net,
            COALESCE(SUM(vat_amount),   0)                                      AS costs_vat,
            COUNT(CASE WHEN is_correction = FALSE THEN 1 END)                   AS expense_count,
            COUNT(CASE WHEN is_correction = TRUE  THEN 1 END)                   AS correction_count
        FROM ksef_invoices
        WHERE studio_id      = :studioId
          AND status        NOT IN ('CANCELLED', 'EXCLUDED')
          AND invoicing_date >= CAST(:dateFrom AS TIMESTAMPTZ)
          AND invoicing_date  < CAST(:dateTo   AS TIMESTAMPTZ)
        GROUP BY DATE_TRUNC('month', invoicing_date)
        ORDER BY month_label ASC
    """, nativeQuery = true)
    fun findMonthlyStatistics(
        @Param("studioId") studioId: UUID,
        @Param("dateFrom") dateFrom: OffsetDateTime,
        @Param("dateTo") dateTo: OffsetDateTime
    ): List<Array<Any?>>

    /**
     * Year-total expense summary (same filters as findMonthlyStatistics).
     * Result columns: costs_gross, costs_net, costs_vat, expense_count, correction_count
     */
    @Query(value = """
        SELECT
            COALESCE(SUM(gross_amount), 0)                                      AS costs_gross,
            COALESCE(SUM(net_amount),   0)                                      AS costs_net,
            COALESCE(SUM(vat_amount),   0)                                      AS costs_vat,
            COUNT(CASE WHEN is_correction = FALSE THEN 1 END)                   AS expense_count,
            COUNT(CASE WHEN is_correction = TRUE  THEN 1 END)                   AS correction_count
        FROM ksef_invoices
        WHERE studio_id      = :studioId
          AND status        NOT IN ('CANCELLED', 'EXCLUDED')
          AND invoicing_date >= CAST(:dateFrom AS TIMESTAMPTZ)
          AND invoicing_date  < CAST(:dateTo   AS TIMESTAMPTZ)
    """, nativeQuery = true)
    fun findTotalStatistics(
        @Param("studioId") studioId: UUID,
        @Param("dateFrom") dateFrom: OffsetDateTime,
        @Param("dateTo") dateTo: OffsetDateTime
    ): Array<Any?>
}
