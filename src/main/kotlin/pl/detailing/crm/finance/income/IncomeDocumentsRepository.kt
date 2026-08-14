package pl.detailing.crm.finance.income

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

/**
 * Odczyt zunifikowanej listy dokumentów przychodowych z dwóch źródeł:
 *
 * 1. [ksef_revenue_invoices] — faktury: wystawione w CRM i wysłane do KSeF (CRM)
 *    oraz pobrane z KSeF, a wystawione poza CRM (EXTERNAL),
 * 2. [financial_documents]  — paragony i dokumenty „inne" (kierunek INCOME).
 *    Faktury powiązane z rekordem KSeF (ksef_revenue_invoice_id) są pomijane,
 *    bo reprezentuje je już źródło 1; faktury bez powiązania to dokumenty
 *    sprzed wdrożenia KSeF i prezentujemy je jako faktury spoza KSeF.
 *
 * Zapytanie natywne z UNION ALL, bo tylko ono pozwala sortować i stronicować
 * po obu źródłach naraz (sortowanie/limit po stronie bazy, nie w pamięci).
 */
@Repository
class IncomeDocumentsRepository(
    @PersistenceContext private val entityManager: EntityManager
) {

    /**
     * Kolejność kolumn odpowiada [IncomeDocumentRow.from]:
     * 0 id, 1 sourceKind, 2 documentType, 3 documentNumber, 4 issueDate,
     * 5 counterpartyName, 6 counterpartyNip, 7 totalNet, 8 totalVat, 9 totalGross,
     * 10 currency, 11 paymentStatus, 12 paymentLabel, 13 ksefStatus, 14 ksefNumber,
     * 15 origin, 16 duplicateStatus, 17 visitId, 18 createdAt
     */
    /**
     * UWAGA: w natywnych zapytaniach nie wolno używać postgresowej składni rzutowania `::`,
     * bo Hibernate traktuje `:nazwa` jako parametr nazwany i zjada jeden dwukropek
     * (`id::text` → `id:text` → błąd składni). Stąd wszędzie CAST(... AS ...).
     * Pilnuje tego IncomeDocumentsRepositorySqlTest.
     */
    internal val selectColumns = """
        SELECT
            CAST(i.id AS text)                      AS id,
            'KSEF'                                  AS source_kind,
            CASE WHEN i.invoice_type = 'KOR' THEN 'CORRECTION' ELSE 'INVOICE' END AS document_type,
            i.invoice_number                        AS document_number,
            i.issue_date                            AS issue_date,
            i.buyer_name                            AS counterparty_name,
            i.buyer_nip                             AS counterparty_nip,
            i.total_net                             AS total_net,
            i.total_vat                             AS total_vat,
            i.total_gross                           AS total_gross,
            i.currency                              AS currency,
            i.payment_status                        AS payment_status,
            i.payment_form                          AS payment_label,
            i.ksef_status                           AS ksef_status,
            i.ksef_number                           AS ksef_number,
            i.source                                AS origin,
            i.duplicate_status                      AS duplicate_status,
            CAST(i.visit_id AS text)                AS visit_id,
            i.created_at                            AS created_at
        FROM ksef_revenue_invoices i
        WHERE i.studio_id = CAST(:studioId AS uuid)
          AND (CAST(:documentType AS text) IS NULL
               OR (CAST(:documentType AS text) = 'INVOICE'    AND i.invoice_type = 'VAT')
               OR (CAST(:documentType AS text) = 'CORRECTION' AND i.invoice_type = 'KOR'))
          AND (CAST(:paymentStatus AS text) IS NULL OR i.payment_status = CAST(:paymentStatus AS text))
          AND (CAST(:dateFrom AS date) IS NULL OR i.issue_date >= CAST(:dateFrom AS date))
          AND (CAST(:dateTo   AS date) IS NULL OR i.issue_date <= CAST(:dateTo   AS date))

        UNION ALL

        SELECT
            CAST(d.id AS text),
            'FINANCE',
            d.document_type,
            d.document_number,
            d.issue_date,
            d.counterparty_name,
            d.counterparty_nip,
            d.total_net,
            d.total_vat,
            d.total_gross,
            d.currency,
            d.status,
            d.payment_method,
            NULL,
            NULL,
            d.source,
            'NONE',
            CAST(d.visit_id AS text),
            d.created_at
        FROM financial_documents d
        WHERE d.studio_id = CAST(:studioId AS uuid)
          AND d.direction = 'INCOME'
          AND d.deleted_at IS NULL
          AND d.ksef_revenue_invoice_id IS NULL
          AND (CAST(:documentType AS text) IS NULL OR d.document_type = CAST(:documentType AS text))
          AND (CAST(:paymentStatus AS text) IS NULL OR d.status = CAST(:paymentStatus AS text))
          AND (CAST(:dateFrom AS date) IS NULL OR d.issue_date >= CAST(:dateFrom AS date))
          AND (CAST(:dateTo   AS date) IS NULL OR d.issue_date <= CAST(:dateTo   AS date))
          AND CAST(:onlyKsef AS boolean) = FALSE
    """

    fun findPage(filters: IncomeDocumentFilters, limit: Int, offset: Int): List<IncomeDocumentRow> {
        val query = entityManager.createNativeQuery(
            "SELECT * FROM ($selectColumns) AS docs ORDER BY docs.issue_date DESC, docs.created_at DESC LIMIT :limit OFFSET :offset"
        )
        bind(query, filters)
        query.setParameter("limit", limit)
        query.setParameter("offset", offset)

        @Suppress("UNCHECKED_CAST")
        val rows = query.resultList as List<Array<Any?>>
        return rows.map { IncomeDocumentRow.from(it) }
    }

    fun count(filters: IncomeDocumentFilters): Long {
        val query = entityManager.createNativeQuery("SELECT COUNT(*) FROM ($selectColumns) AS docs")
        bind(query, filters)
        return (query.singleResult as Number).toLong()
    }

    private fun bind(query: jakarta.persistence.Query, filters: IncomeDocumentFilters) {
        query.setParameter("studioId", filters.studioId)
        query.setParameter("documentType", filters.documentType)
        query.setParameter("paymentStatus", filters.paymentStatus)
        query.setParameter("dateFrom", filters.dateFrom)
        query.setParameter("dateTo", filters.dateTo)
        query.setParameter("onlyKsef", filters.onlyKsef)
    }
}

data class IncomeDocumentFilters(
    val studioId: UUID,
    /** INVOICE | CORRECTION | RECEIPT | OTHER; null = wszystkie typy. */
    val documentType: String? = null,
    /** PAID | PENDING | OVERDUE; null = wszystkie. */
    val paymentStatus: String? = null,
    val dateFrom: LocalDate? = null,
    val dateTo: LocalDate? = null,
    /** true = tylko dokumenty z ledgera KSeF (faktury i korekty). */
    val onlyKsef: Boolean = false
)
