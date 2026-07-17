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

    fun findByStudioIdAndKsefNumber(studioId: UUID, ksefNumber: String): KsefInvoiceEntity?

    /**
     * Faktury KSeF bez pełnych danych z XML (details_synced = FALSE) — kandydaci do
     * synchronizacji wstecznej. Paginacja ogranicza liczbę pobrań XML w jednym przebiegu.
     */
    fun findByStudioIdAndSourceAndDetailsSyncedFalseOrderByFetchedAtDesc(
        studioId: UUID,
        source: String,
        pageable: Pageable
    ): List<KsefInvoiceEntity>

    /**
     * Paginated listing with optional source and paymentStatus filters.
     * EXCLUDED invoices are hidden by default (pass includeExcluded=true to show them).
     *
     * Native query with explicit CASTs to avoid PostgreSQL "could not determine data type"
     * errors when nullable parameters are passed as $N bind variables.
     */
    @Query(value = """
        SELECT * FROM ksef_invoices i
        WHERE i.studio_id = CAST(:studioId AS uuid)
          AND (:includeExcluded = true OR i.status <> 'EXCLUDED')
          AND (CAST(:source AS text) IS NULL OR i.source = CAST(:source AS text))
          AND (CAST(:paymentStatus AS text) IS NULL OR i.payment_status = CAST(:paymentStatus AS text))
          AND (CAST(:dateFrom AS timestamptz) IS NULL OR i.invoicing_date >= CAST(:dateFrom AS timestamptz))
          AND (CAST(:dateTo   AS timestamptz) IS NULL OR i.invoicing_date <= CAST(:dateTo   AS timestamptz))
        ORDER BY i.invoicing_date DESC NULLS LAST, i.fetched_at DESC
    """, countQuery = """
        SELECT COUNT(*) FROM ksef_invoices i
        WHERE i.studio_id = CAST(:studioId AS uuid)
          AND (:includeExcluded = true OR i.status <> 'EXCLUDED')
          AND (CAST(:source AS text) IS NULL OR i.source = CAST(:source AS text))
          AND (CAST(:paymentStatus AS text) IS NULL OR i.payment_status = CAST(:paymentStatus AS text))
          AND (CAST(:dateFrom AS timestamptz) IS NULL OR i.invoicing_date >= CAST(:dateFrom AS timestamptz))
          AND (CAST(:dateTo   AS timestamptz) IS NULL OR i.invoicing_date <= CAST(:dateTo   AS timestamptz))
    """, nativeQuery = true)
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

    @Modifying
    @Query("UPDATE KsefInvoiceEntity i SET i.note = :note WHERE i.id = :id AND i.studioId = :studioId")
    fun updateNote(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID,
        @Param("note") note: String?
    ): Int

    // ── Finance summary aggregates ────────────────────────────────────────────

    /**
     * Sum of gross_amount (in PLN) for invoices matching the given paymentStatus,
     * excluding CANCELLED and EXCLUDED. Multiply result by 100 to get grosz.
     * dateFrom/dateTo filter on issue_date (LocalDate); pass null to ignore.
     */
    @Query(value = """
        SELECT COALESCE(SUM(gross_amount), 0)
        FROM ksef_invoices
        WHERE studio_id      = CAST(:studioId AS uuid)
          AND payment_status = :paymentStatus
          AND status        NOT IN ('CANCELLED', 'EXCLUDED')
          AND (CAST(:dateFrom AS date) IS NULL OR issue_date >= CAST(:dateFrom AS date))
          AND (CAST(:dateTo   AS date) IS NULL OR issue_date <= CAST(:dateTo   AS date))
    """, nativeQuery = true)
    fun sumGrossByPaymentStatus(
        @Param("studioId") studioId: UUID,
        @Param("paymentStatus") paymentStatus: String,
        @Param("dateFrom") dateFrom: java.time.LocalDate?,
        @Param("dateTo") dateTo: java.time.LocalDate?
    ): Double

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
