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

    fun findAllByStudioId(studioId: UUID, pageable: Pageable): Page<KsefInvoiceEntity>

    fun existsByStudioIdAndKsefNumber(studioId: UUID, ksefNumber: String): Boolean

    fun countByStudioId(studioId: UUID): Long

    fun findByStudioIdAndKsefNumber(studioId: UUID, ksefNumber: String): KsefInvoiceEntity?

    // ─── Aktualizacja statusu ─────────────────────────────────────────────────

    @Modifying
    @Query("UPDATE KsefInvoiceEntity i SET i.status = :status WHERE i.studioId = :studioId AND i.ksefNumber = :ksefNumber")
    fun updateStatus(
        @Param("studioId") studioId: UUID,
        @Param("ksefNumber") ksefNumber: String,
        @Param("status") status: String
    ): Int

    // ─── Statystyki miesięczne (native SQL dla wydajności) ────────────────────

    /**
     * Zwraca miesięczne podsumowanie przychodów i kosztów dla danego studio.
     * Korekty (FA_KOR) są wliczane automatycznie – ich kwoty mają odpowiedni znak
     * po stronie KSeF, więc SUM() daje prawidłowy wynik netto.
     *
     * Kolumny wyniku (w kolejności):
     * 0  month_label      – format 'YYYY-MM'
     * 1  revenue_gross    – przychody brutto (INCOME)
     * 2  revenue_net      – przychody netto (INCOME)
     * 3  revenue_vat      – VAT od przychodów (INCOME)
     * 4  costs_gross      – koszty brutto (EXPENSE)
     * 5  costs_net        – koszty netto (EXPENSE)
     * 6  costs_vat        – VAT od kosztów (EXPENSE)
     * 7  income_count     – liczba faktur przychodowych (bez korekt)
     * 8  expense_count    – liczba faktur kosztowych (bez korekt)
     * 9  correction_count – liczba korekt (wszystkie kierunki)
     */
    @Query(value = """
        SELECT
            TO_CHAR(DATE_TRUNC('month', invoicing_date), 'YYYY-MM')            AS month_label,
            COALESCE(SUM(CASE WHEN direction = 'INCOME'  THEN gross_amount ELSE 0 END), 0) AS revenue_gross,
            COALESCE(SUM(CASE WHEN direction = 'INCOME'  THEN net_amount   ELSE 0 END), 0) AS revenue_net,
            COALESCE(SUM(CASE WHEN direction = 'INCOME'  THEN vat_amount   ELSE 0 END), 0) AS revenue_vat,
            COALESCE(SUM(CASE WHEN direction = 'EXPENSE' THEN gross_amount ELSE 0 END), 0) AS costs_gross,
            COALESCE(SUM(CASE WHEN direction = 'EXPENSE' THEN net_amount   ELSE 0 END), 0) AS costs_net,
            COALESCE(SUM(CASE WHEN direction = 'EXPENSE' THEN vat_amount   ELSE 0 END), 0) AS costs_vat,
            COUNT(CASE WHEN direction = 'INCOME'  AND is_correction = FALSE THEN 1 END)    AS income_count,
            COUNT(CASE WHEN direction = 'EXPENSE' AND is_correction = FALSE THEN 1 END)    AS expense_count,
            COUNT(CASE WHEN is_correction = TRUE THEN 1 END)                               AS correction_count
        FROM ksef_invoices
        WHERE studio_id       = :studioId
          AND status         != 'CANCELLED'
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
     * Łączne sumy dla całego zakresu dat (bez podziału na miesiące).
     * Zwraca pojedynczy wiersz z kolumnami w kolejności identycznej jak findMonthlyStatistics
     * (bez month_label – 9 wartości zamiast 10).
     */
    @Query(value = """
        SELECT
            COALESCE(SUM(CASE WHEN direction = 'INCOME'  THEN gross_amount ELSE 0 END), 0) AS revenue_gross,
            COALESCE(SUM(CASE WHEN direction = 'INCOME'  THEN net_amount   ELSE 0 END), 0) AS revenue_net,
            COALESCE(SUM(CASE WHEN direction = 'INCOME'  THEN vat_amount   ELSE 0 END), 0) AS revenue_vat,
            COALESCE(SUM(CASE WHEN direction = 'EXPENSE' THEN gross_amount ELSE 0 END), 0) AS costs_gross,
            COALESCE(SUM(CASE WHEN direction = 'EXPENSE' THEN net_amount   ELSE 0 END), 0) AS costs_net,
            COALESCE(SUM(CASE WHEN direction = 'EXPENSE' THEN vat_amount   ELSE 0 END), 0) AS costs_vat,
            COUNT(CASE WHEN direction = 'INCOME'  AND is_correction = FALSE THEN 1 END)    AS income_count,
            COUNT(CASE WHEN direction = 'EXPENSE' AND is_correction = FALSE THEN 1 END)    AS expense_count,
            COUNT(CASE WHEN is_correction = TRUE THEN 1 END)                               AS correction_count
        FROM ksef_invoices
        WHERE studio_id       = :studioId
          AND status         != 'CANCELLED'
          AND invoicing_date >= CAST(:dateFrom AS TIMESTAMPTZ)
          AND invoicing_date  < CAST(:dateTo   AS TIMESTAMPTZ)
    """, nativeQuery = true)
    fun findTotalStatistics(
        @Param("studioId") studioId: UUID,
        @Param("dateFrom") dateFrom: OffsetDateTime,
        @Param("dateTo") dateTo: OffsetDateTime
    ): Array<Any?>
}
