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
     * 15 origin, 16 duplicateStatus, 17 visitId, 18 createdAt, 19 excluded, 20 note
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
            i.created_at                            AS created_at,
            (i.excluded_at IS NOT NULL)             AS is_excluded,
            i.note                                  AS note
        FROM ksef_revenue_invoices i
        WHERE i.studio_id = CAST(:studioId AS uuid)
          AND (CAST(:documentType AS text) IS NULL
               OR (CAST(:documentType AS text) = 'INVOICE'    AND i.invoice_type = 'VAT')
               OR (CAST(:documentType AS text) = 'CORRECTION' AND i.invoice_type = 'KOR'))
          AND (CAST(:paymentStatus AS text) IS NULL OR i.payment_status = CAST(:paymentStatus AS text))
          AND (CAST(:dateFrom AS date) IS NULL OR i.issue_date >= CAST(:dateFrom AS date))
          AND (CAST(:dateTo   AS date) IS NULL OR i.issue_date <= CAST(:dateTo   AS date))
          AND ((CAST(:onlyExcluded AS boolean) = TRUE AND i.excluded_at IS NOT NULL)
               OR (CAST(:onlyExcluded AS boolean) = FALSE
                   AND (CAST(:includeExcluded AS boolean) = TRUE OR i.excluded_at IS NULL)))
          AND (CAST(:search AS text) IS NULL
               OR LOWER(COALESCE(i.invoice_number, '')) LIKE CAST(:search AS text)
               OR LOWER(COALESCE(i.ksef_number, ''))    LIKE CAST(:search AS text)
               OR LOWER(COALESCE(i.buyer_name, ''))     LIKE CAST(:search AS text)
               OR LOWER(COALESCE(i.buyer_nip, ''))      LIKE CAST(:search AS text)
               OR LOWER(COALESCE(i.seller_name, ''))    LIKE CAST(:search AS text)
               OR LOWER(COALESCE(i.seller_nip, ''))     LIKE CAST(:search AS text)
               OR (CAST(:searchDigits AS text) IS NOT NULL
                   AND (regexp_replace(COALESCE(i.buyer_nip, ''),  '\D', '', 'g') LIKE CAST(:searchDigits AS text)
                     OR regexp_replace(COALESCE(i.seller_nip, ''), '\D', '', 'g') LIKE CAST(:searchDigits AS text)))
               OR (CAST(:searchAmount AS text) IS NOT NULL
                   AND (TO_CHAR(i.total_gross / 100.0, 'FM9999999990.00') LIKE CAST(:searchAmount AS text)
                     OR TO_CHAR(i.total_net   / 100.0, 'FM9999999990.00') LIKE CAST(:searchAmount AS text)))
               OR EXISTS (SELECT 1 FROM ksef_revenue_invoice_items it
                          WHERE it.invoice_id = i.id
                            AND LOWER(it.name) LIKE CAST(:search AS text)))

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
            d.created_at,
            (d.excluded_at IS NOT NULL),
            d.note
        FROM financial_documents d
        WHERE d.studio_id = CAST(:studioId AS uuid)
          AND d.direction = 'INCOME'
          AND d.deleted_at IS NULL
          AND d.ksef_revenue_invoice_id IS NULL
          AND (CAST(:documentType AS text) IS NULL OR d.document_type = CAST(:documentType AS text))
          AND (CAST(:paymentStatus AS text) IS NULL OR d.status = CAST(:paymentStatus AS text))
          AND (CAST(:dateFrom AS date) IS NULL OR d.issue_date >= CAST(:dateFrom AS date))
          AND (CAST(:dateTo   AS date) IS NULL OR d.issue_date <= CAST(:dateTo   AS date))
          AND ((CAST(:onlyExcluded AS boolean) = TRUE AND d.excluded_at IS NOT NULL)
               OR (CAST(:onlyExcluded AS boolean) = FALSE
                   AND (CAST(:includeExcluded AS boolean) = TRUE OR d.excluded_at IS NULL)))
          AND CAST(:onlyKsef AS boolean) = FALSE
          AND (CAST(:search AS text) IS NULL
               OR LOWER(d.document_number)                    LIKE CAST(:search AS text)
               OR LOWER(COALESCE(d.counterparty_name, ''))    LIKE CAST(:search AS text)
               OR LOWER(COALESCE(d.counterparty_nip, ''))     LIKE CAST(:search AS text)
               OR LOWER(COALESCE(d.description, ''))          LIKE CAST(:search AS text)
               OR (CAST(:searchDigits AS text) IS NOT NULL
                   AND regexp_replace(COALESCE(d.counterparty_nip, ''), '\D', '', 'g') LIKE CAST(:searchDigits AS text))
               OR (CAST(:searchAmount AS text) IS NOT NULL
                   AND (TO_CHAR(d.total_gross / 100.0, 'FM9999999990.00') LIKE CAST(:searchAmount AS text)
                     OR TO_CHAR(d.total_net   / 100.0, 'FM9999999990.00') LIKE CAST(:searchAmount AS text))))
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
        query.setParameter("includeExcluded", filters.includeExcluded)
        query.setParameter("onlyExcluded", filters.onlyExcluded)
        query.setParameter("search", filters.search)
        query.setParameter("searchDigits", filters.searchDigits)
        query.setParameter("searchAmount", filters.searchAmount)
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
    val onlyKsef: Boolean = false,
    /**
     * true = pokaż także dokumenty ukryte ręcznie ze statystyk. Domyślnie ukryte
     * pozycje nie pojawiają się na liście, tak jak po stronie dokumentów kosztowych.
     */
    val includeExcluded: Boolean = false,
    /** true = pokaż WYŁĄCZNIE ukryte. Ma pierwszeństwo nad [includeExcluded]. */
    val onlyExcluded: Boolean = false,
    /**
     * Fraza wyszukiwarki jako wzorzec `%…%` małymi literami — szuka po numerze dokumentu,
     * numerze KSeF, nazwie i NIP-ie kontrahenta, opisie oraz nazwach pozycji faktury.
     * Buduje ją [pl.detailing.crm.shared.SearchTerm.like]; null = bez wyszukiwania.
     */
    val search: String? = null,
    /** Ta sama fraza zredukowana do cyfr — NIP dopasowany mimo prefiksu „PL" i myślników. */
    val searchDigits: String? = null,
    /** Ta sama fraza jako kwota w złotych; null, gdy fraza nie wygląda na kwotę. */
    val searchAmount: String? = null
)
