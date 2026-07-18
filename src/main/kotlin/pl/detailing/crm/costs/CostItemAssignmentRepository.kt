package pl.detailing.crm.costs

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface CostItemAssignmentRepository : JpaRepository<CostItemAssignmentEntity, UUID> {

    fun findByKsefItemId(ksefItemId: UUID): CostItemAssignmentEntity?

    fun findByCategoryIdAndStudioId(categoryId: UUID, studioId: UUID): List<CostItemAssignmentEntity>

    /** All assignments for the studio — used when building the full expense-items response. */
    fun findByStudioId(studioId: UUID): List<CostItemAssignmentEntity>

    @Modifying
    @Query("DELETE FROM CostItemAssignmentEntity a WHERE a.ksefItemId = :itemId AND a.studioId = :studioId")
    fun deleteByKsefItemIdAndStudioId(
        @Param("itemId") itemId: UUID,
        @Param("studioId") studioId: UUID
    ): Int

    @Modifying
    @Query("DELETE FROM CostItemAssignmentEntity a WHERE a.categoryId = :categoryId AND a.studioId = :studioId")
    fun deleteByCategoryIdAndStudioId(
        @Param("categoryId") categoryId: UUID,
        @Param("studioId") studioId: UUID
    ): Int

    /** Native query: sum gross_value of assigned items per category in a date window. */
    @Query(value = """
        SELECT
            cia.category_id                          AS categoryId,
            COALESCE(SUM(kii.gross_value), 0)        AS totalGross,
            COALESCE(SUM(kii.net_value), 0)          AS totalNet,
            COUNT(*)                                 AS itemCount
        FROM cost_item_assignments cia
        JOIN ksef_invoice_items kii ON kii.id = cia.ksef_item_id
        JOIN ksef_invoices ki ON ki.id = kii.invoice_id
        WHERE cia.studio_id = CAST(:studioId AS uuid)
          AND ki.status NOT IN ('CANCELLED', 'EXCLUDED')
          AND (CAST(:dateFrom AS date) IS NULL OR CAST(ki.invoicing_date AS date) >= CAST(:dateFrom AS date))
          AND (CAST(:dateTo   AS date) IS NULL OR CAST(ki.invoicing_date AS date) <= CAST(:dateTo   AS date))
        GROUP BY cia.category_id
    """, nativeQuery = true)
    fun sumByCategory(
        @Param("studioId") studioId: UUID,
        @Param("dateFrom") dateFrom: String?,
        @Param("dateTo")   dateTo:   String?
    ): List<Array<Any?>>

    /** Time-series: total cost gross per period (for the overview chart). */
    @Query(value = """
        SELECT
            TO_CHAR(CAST(ki.invoicing_date AS date), :periodFormat)  AS period,
            COALESCE(SUM(kii.gross_value), 0)                        AS totalCostGross,
            COUNT(*)                                                  AS itemCount
        FROM ksef_invoice_items kii
        JOIN ksef_invoices ki ON ki.id = kii.invoice_id
        WHERE ki.studio_id = CAST(:studioId AS uuid)
          AND ki.status NOT IN ('CANCELLED', 'EXCLUDED')
          AND (CAST(:dateFrom AS date) IS NULL OR CAST(ki.invoicing_date AS date) >= CAST(:dateFrom AS date))
          AND (CAST(:dateTo   AS date) IS NULL OR CAST(ki.invoicing_date AS date) <= CAST(:dateTo   AS date))
        GROUP BY TO_CHAR(CAST(ki.invoicing_date AS date), :periodFormat)
        ORDER BY period ASC
    """, nativeQuery = true)
    fun findTimeSeriesAllItems(
        @Param("studioId")    studioId:    UUID,
        @Param("dateFrom")    dateFrom:    String?,
        @Param("dateTo")      dateTo:      String?,
        @Param("periodFormat") periodFormat: String
    ): List<Array<Any?>>

    /** Time-series for a single category. */
    @Query(value = """
        SELECT
            TO_CHAR(CAST(ki.invoicing_date AS date), :periodFormat)  AS period,
            COALESCE(SUM(kii.gross_value), 0)                        AS totalCostGross,
            COUNT(*)                                                  AS itemCount
        FROM cost_item_assignments cia
        JOIN ksef_invoice_items kii ON kii.id = cia.ksef_item_id
        JOIN ksef_invoices ki ON ki.id = kii.invoice_id
        WHERE cia.studio_id    = CAST(:studioId    AS uuid)
          AND cia.category_id  = CAST(:categoryId  AS uuid)
          AND ki.status NOT IN ('CANCELLED', 'EXCLUDED')
          AND (CAST(:dateFrom AS date) IS NULL OR CAST(ki.invoicing_date AS date) >= CAST(:dateFrom AS date))
          AND (CAST(:dateTo   AS date) IS NULL OR CAST(ki.invoicing_date AS date) <= CAST(:dateTo   AS date))
        GROUP BY TO_CHAR(CAST(ki.invoicing_date AS date), :periodFormat)
        ORDER BY period ASC
    """, nativeQuery = true)
    fun findTimeSeriesByCategory(
        @Param("studioId")    studioId:    UUID,
        @Param("categoryId")  categoryId:  UUID,
        @Param("dateFrom")    dateFrom:    String?,
        @Param("dateTo")      dateTo:      String?,
        @Param("periodFormat") periodFormat: String
    ): List<Array<Any?>>
}
