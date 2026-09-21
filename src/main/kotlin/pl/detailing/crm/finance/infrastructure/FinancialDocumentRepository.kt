package pl.detailing.crm.finance.infrastructure

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import pl.detailing.crm.finance.domain.DocumentDirection
import pl.detailing.crm.finance.domain.DocumentStatus
import pl.detailing.crm.finance.domain.DocumentType
import java.time.LocalDate
import java.util.UUID

@Repository
interface FinancialDocumentRepository : JpaRepository<FinancialDocumentEntity, UUID> {

    @Query("SELECT d FROM FinancialDocumentEntity d WHERE d.id = :id AND d.studioId = :studioId AND d.deletedAt IS NULL")
    fun findByIdAndStudioId(id: UUID, studioId: UUID): FinancialDocumentEntity?

    @Query("SELECT d FROM FinancialDocumentEntity d WHERE d.id = :id AND d.studioId = :studioId")
    fun findByIdAndStudioIdIncludingDeleted(id: UUID, studioId: UUID): FinancialDocumentEntity?

    /** Zaznaczone dokumenty w jednym zapytaniu — dla operacji grupowych na liście przychodów. */
    @Query("SELECT d FROM FinancialDocumentEntity d WHERE d.id IN :ids AND d.studioId = :studioId AND d.deletedAt IS NULL")
    fun findAllByIdInAndStudioId(ids: Collection<UUID>, studioId: UUID): List<FinancialDocumentEntity>

    @Query("SELECT d FROM FinancialDocumentEntity d WHERE d.visitId = :visitId AND d.studioId = :studioId AND d.deletedAt IS NULL")
    fun findAllByVisitIdAndStudioIdAndDeletedAtIsNull(visitId: UUID, studioId: UUID): List<FinancialDocumentEntity>

    @Query("""
        SELECT COUNT(d) FROM FinancialDocumentEntity d
        WHERE d.studioId = :studioId
          AND d.documentType = :documentType
          AND d.issueDate >= :yearStart
          AND d.issueDate < :yearEnd
          AND d.deletedAt IS NULL
    """)
    fun countByStudioTypeAndYear(
        studioId: UUID,
        documentType: DocumentType,
        yearStart: LocalDate,
        yearEnd: LocalDate
    ): Long

    @Query("""
        SELECT d FROM FinancialDocumentEntity d
        WHERE d.studioId = :studioId
          AND (:includeDeleted = true OR d.deletedAt IS NULL)
          AND (:documentType IS NULL OR d.documentType = :documentType)
          AND (:direction    IS NULL OR d.direction    = :direction)
          AND (:status       IS NULL OR d.status       = :status)
          AND (:visitId      IS NULL OR d.visitId      = :visitId)
          AND d.issueDate >= COALESCE(:dateFrom, d.issueDate)
          AND d.issueDate <= COALESCE(:dateTo,   d.issueDate)
    """)
    fun findWithFilters(
        studioId: UUID,
        documentType: DocumentType?,
        direction: DocumentDirection?,
        status: DocumentStatus?,
        visitId: UUID?,
        dateFrom: LocalDate?,
        dateTo: LocalDate?,
        includeDeleted: Boolean,
        pageable: Pageable
    ): Page<FinancialDocumentEntity>

    /**
     * Suma netto dokumentów finansowych — podstawa kafli podsumowania finansów.
     *
     * Netto, a nie brutto: VAT jest pieniądzem urzędu skarbowego, tylko
     * przechodzącym przez konto studia. Przychód, koszt i zysk liczone brutto
     * zawyżały wynik o stawkę VAT i nie dawały się porównać z kosztami, w których
     * VAT jest odliczany. Kolumna `total_net` jest wiarygodna: domena
     * FinancialDocument pilnuje niezmiennika netto + VAT = brutto przy zapisie.
     *
     * Dokumenty powiązane z fakturą w ledgerze KSeF (ksefRevenueInvoiceId) są
     * pomijane — reprezentuje je tam już rekord faktury, a to on niesie korekty.
     * Liczenie obu stron dawałoby podwójny przychód i ignorowałoby korekty.
     * Kolumna dotyczy wyłącznie kierunku INCOME, więc dla EXPENSE warunek jest
     * bezskutkowy.
     *
     * Dokumenty ukryte ręcznie (excludedAt) nie wchodzą do sum — o to chodzi
     * w ukrywaniu: pozycja znika ze statystyk, zostając w bazie.
     *
     * [statuses] jest zbiorem, a nie pojedynczą wartością, bo należność
     * przeterminowana to wciąż należność: OVERDUE musi sumować się razem
     * z PENDING. Wcześniejsza wersja przyjmowała jeden status i dokument
     * przeterminowany był wart zero złotych po obu stronach raportu.
     */
    /*
     * Zakres dat przez COALESCE, nie przez „(:dateFrom IS NULL OR …)".
     *
     * Zapis z IS NULL wygląda naturalniej, ale w Postgresie wywraca całe
     * zapytanie, gdy data jest pusta: Hibernate wypisuje parametr DWA razy
     * (`$4 is null or issue_date >= $5`), a pierwsze wystąpienie stoi samotnie
     * przy IS NULL i nie ma z czego wywnioskować typu. Sterownik wysyła wtedy
     * NULL bez typu, a serwer odpowiada `could not determine data type of
     * parameter $4` (SQLState 42P18) - kafle „Podsumowanie finansowe" nie
     * ładowały się wcale, gdy zakresem był „Cały czas".
     *
     * `d.issueDate >= COALESCE(:dateFrom, d.issueDate)` zostawia parametr
     * wyłącznie w miejscu, gdzie sąsiaduje z kolumną typu date - typ jest znany
     * także dla NULL-a, a pusta data znaczy „bez dolnej granicy", bo warunek
     * schodzi wtedy do `issueDate >= issueDate`. Kolumna jest NOT NULL, więc
     * porównanie nie ma jak dać NULL-a i wyciąć wiersza.
     *
     * Zapytania natywne w tym module rozwiązują to samo przez CAST (patrz
     * IncomeDocumentsRepository, KsefInvoiceRepository); w JPQL COALESCE jest
     * krótszy i nie powtarza typu kolumny w dwóch miejscach.
     */
    @Query("""
        SELECT COALESCE(SUM(d.totalNet), 0) FROM FinancialDocumentEntity d
        WHERE d.studioId  = :studioId
          AND d.direction = :direction
          AND d.status    IN :statuses
          AND d.deletedAt IS NULL
          AND d.excludedAt IS NULL
          AND d.ksefRevenueInvoiceId IS NULL
          AND d.issueDate >= COALESCE(:dateFrom, d.issueDate)
          AND d.issueDate <= COALESCE(:dateTo,   d.issueDate)
    """)
    fun sumNet(
        studioId: UUID,
        direction: DocumentDirection,
        statuses: Collection<DocumentStatus>,
        dateFrom: LocalDate?,
        dateTo: LocalDate?
    ): Long

    @Query("""
        SELECT COUNT(d) FROM FinancialDocumentEntity d
        WHERE d.studioId  = :studioId
          AND d.status    = 'OVERDUE'
          AND d.deletedAt IS NULL
          AND d.excludedAt IS NULL
          AND (:direction IS NULL OR d.direction = :direction)
    """)
    fun countOverdue(studioId: UUID, direction: DocumentDirection?): Long

    @Query("""
        SELECT d FROM FinancialDocumentEntity d
        WHERE d.studioId  = :studioId
          AND d.status    = 'PENDING'
          AND d.dueDate   < :today
          AND d.deletedAt IS NULL
    """)
    fun findPendingOverdue(studioId: UUID, today: LocalDate): List<FinancialDocumentEntity>

    @Modifying
    @Query("""
        UPDATE FinancialDocumentEntity d
        SET d.status = :newStatus, d.updatedAt = CURRENT_TIMESTAMP
        WHERE d.studioId = :studioId
          AND d.status   = 'PENDING'
          AND d.dueDate  < :today
          AND d.deletedAt IS NULL
    """)
    fun markOverdueBatch(studioId: UUID, today: LocalDate, newStatus: DocumentStatus): Int

    @Query(value = """
        SELECT * FROM financial_documents d
        WHERE d.studio_id  = CAST(:studioId AS uuid)
          AND d.direction  = 'INCOME'
          AND d.status     = 'PAID'
          AND d.deleted_at IS NULL
          AND d.excluded_at IS NULL
          AND (CAST(:documentType AS text) IS NULL OR d.document_type = CAST(:documentType AS text))
          AND (CAST(:dateFrom AS text) IS NULL OR d.issue_date >= CAST(:dateFrom AS date))
          AND (CAST(:dateTo   AS text) IS NULL OR d.issue_date <= CAST(:dateTo   AS date))
        ORDER BY d.issue_date ASC
    """, nativeQuery = true)
    fun findPaidIncomeForReport(
        studioId: UUID,
        documentType: String?,
        dateFrom: LocalDate?,
        dateTo: LocalDate?
    ): List<FinancialDocumentEntity>
}
