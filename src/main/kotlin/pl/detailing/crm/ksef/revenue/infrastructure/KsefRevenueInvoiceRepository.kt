package pl.detailing.crm.ksef.revenue.infrastructure

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import pl.detailing.crm.ksef.revenue.domain.KsefRevenueStatus
import pl.detailing.crm.ksef.revenue.domain.RevenueSource
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

@Repository
interface KsefRevenueInvoiceRepository : JpaRepository<KsefRevenueInvoiceEntity, UUID> {

    fun findByIdAndStudioId(id: UUID, studioId: UUID): KsefRevenueInvoiceEntity?

    fun findByStudioIdAndKsefNumber(studioId: UUID, ksefNumber: String): KsefRevenueInvoiceEntity?

    /**
     * Nasze faktury czekające na potwierdzenie z KSeF (brak numeru KSeF) o danym
     * numerze własnym. Pull po SUBJECT1 dopasowuje po numerze KSeF; gdy wyprzedzi
     * polling sesji, numeru KSeF jeszcze nie znamy i trzeba dopasować po numerze
     * dokumentu — inaczej powstałby fantomowy rekord EXTERNAL o tym samym numerze.
     */
    @Query(
        """
        SELECT i FROM KsefRevenueInvoiceEntity i
        WHERE i.studioId = :studioId
          AND i.invoiceNumber = :invoiceNumber
          AND i.ksefNumber IS NULL
          AND i.ksefStatus <> pl.detailing.crm.ksef.revenue.domain.KsefRevenueStatus.REJECTED
        """
    )
    fun findAwaitingConfirmationByNumber(
        @Param("studioId") studioId: UUID,
        @Param("invoiceNumber") invoiceNumber: String
    ): List<KsefRevenueInvoiceEntity>

    fun existsByStudioIdAndOriginalInvoiceIdAndKsefStatusNot(
        studioId: UUID,
        originalInvoiceId: UUID,
        ksefStatus: KsefRevenueStatus
    ): Boolean

    /** Faktury do dosłania / dokończenia przez scheduler (wszystkie studia). */
    fun findByKsefStatusIn(statuses: List<KsefRevenueStatus>): List<KsefRevenueInvoiceEntity>

    /**
     * Faktury pobrane z KSeF, którym brakuje szczegółów z XML (pozycji, adresów,
     * płatności) — kandydaci synchronizacji wstecznej. Tylko EXTERNAL: faktury
     * wystawione w CRM mają własny XML i pozycje od chwili wystawienia, więc
     * pobieranie ich z KSeF byłoby wydawaniem limitu żądań na dane, które już mamy.
     *
     * Najnowsze najpierw: świeża faktura jest tą, którą użytkownik zaraz otworzy.
     */
    @Query(
        """
        SELECT i FROM KsefRevenueInvoiceEntity i
        WHERE i.studioId = :studioId
          AND i.source = pl.detailing.crm.ksef.revenue.domain.RevenueSource.EXTERNAL
          AND i.detailsSynced = false
        ORDER BY i.issueDate DESC, i.createdAt DESC
        """
    )
    fun findExternalMissingDetails(
        @Param("studioId") studioId: UUID,
        pageable: Pageable
    ): List<KsefRevenueInvoiceEntity>

    /**
     * Najwyższy numer kolejny w serii {PREFIX}/{rok}/{seq} dla danego NIP-u sprzedawcy —
     * podstawa numeracji FV/{rok}/{seq} i FK/{rok}/{seq}.
     *
     * Zakresem jest NIP sprzedawcy, a NIE studio: KSeF pilnuje unikalności numeru
     * w obrębie NIP-u, więc dwa studia dzielące ten sam NIP nie mogą mieć własnych,
     * niezależnych serii (skutkuje to odrzuceniem kodem 440 „Duplikat faktury").
     *
     * Liczy się MAX, a nie COUNT: usunięcie lub odrzucenie dokumentu nie może
     * spowodować ponownego wydania tego samego numeru. Uwzględniamy również
     * dokumenty EXTERNAL — jeśli faktura o takim numerze istnieje w KSeF pod naszym
     * NIP-em, numer jest zajęty niezależnie od tego, gdzie ją wystawiono.
     *
     * [numberPattern] to regex POSIX, np. `^FV/2026/[0-9]+$` — rok jest brany
     * z numeru, nie z issue_date, bo to numer decyduje o serii.
     */
    @Query(
        value = """
        SELECT COALESCE(MAX(CAST(SPLIT_PART(i.invoice_number, '/', 3) AS integer)), 0)
        FROM ksef_revenue_invoices i
        WHERE i.seller_nip = CAST(:sellerNip AS text)
          AND i.invoice_number ~ CAST(:numberPattern AS text)
    """, nativeQuery = true
    )
    fun findMaxNumberSequence(
        @Param("sellerNip") sellerNip: String,
        @Param("numberPattern") numberPattern: String
    ): Int

    /**
     * Kandydaci do pary duplikatów dla faktury EXTERNAL: faktury CRM z tym samym
     * NIP-em nabywcy, identyczną kwotą brutto i datą wystawienia w oknie ±[days] dni,
     * bez rozstrzygniętego już statusu duplikatu.
     */
    @Query(
        """
        SELECT i FROM KsefRevenueInvoiceEntity i
        WHERE i.studioId = :studioId
          AND i.source = pl.detailing.crm.ksef.revenue.domain.RevenueSource.CRM
          AND i.buyerNip = :buyerNip
          AND i.totalGross = :totalGross
          AND i.issueDate BETWEEN :dateFrom AND :dateTo
          AND i.duplicateStatus IN (
                pl.detailing.crm.ksef.revenue.domain.DuplicateStatus.NONE,
                pl.detailing.crm.ksef.revenue.domain.DuplicateStatus.SUSPECTED
          )
        """
    )
    fun findDuplicateCandidates(
        @Param("studioId") studioId: UUID,
        @Param("buyerNip") buyerNip: String,
        @Param("totalGross") totalGross: Long,
        @Param("dateFrom") dateFrom: LocalDate,
        @Param("dateTo") dateTo: LocalDate
    ): List<KsefRevenueInvoiceEntity>

    /**
     * Lista z opcjonalnymi filtrami. Native query z jawnymi CAST-ami — jak w
     * KsefInvoiceRepository — żeby nullowalne parametry nie wysypywały PostgreSQL.
     *
     * Faktury ukryte ręcznie (excluded_at) są domyślnie niewidoczne — pokazuje je
     * dopiero includeExcluded = true, tak samo jak po stronie dokumentów kosztowych.
     */
    @Query(
        value = """
        SELECT * FROM ksef_revenue_invoices i
        WHERE i.studio_id = CAST(:studioId AS uuid)
          AND (CAST(:source AS text)     IS NULL OR i.source = CAST(:source AS text))
          AND (CAST(:ksefStatus AS text) IS NULL OR i.ksef_status = CAST(:ksefStatus AS text))
          AND (CAST(:duplicateStatus AS text) IS NULL OR i.duplicate_status = CAST(:duplicateStatus AS text))
          AND (CAST(:dateFrom AS date)   IS NULL OR i.issue_date >= CAST(:dateFrom AS date))
          AND (CAST(:dateTo   AS date)   IS NULL OR i.issue_date <= CAST(:dateTo   AS date))
          AND (CAST(:includeExcluded AS boolean) = TRUE OR i.excluded_at IS NULL)
        ORDER BY i.issue_date DESC, i.created_at DESC
    """, countQuery = """
        SELECT COUNT(*) FROM ksef_revenue_invoices i
        WHERE i.studio_id = CAST(:studioId AS uuid)
          AND (CAST(:source AS text)     IS NULL OR i.source = CAST(:source AS text))
          AND (CAST(:ksefStatus AS text) IS NULL OR i.ksef_status = CAST(:ksefStatus AS text))
          AND (CAST(:duplicateStatus AS text) IS NULL OR i.duplicate_status = CAST(:duplicateStatus AS text))
          AND (CAST(:dateFrom AS date)   IS NULL OR i.issue_date >= CAST(:dateFrom AS date))
          AND (CAST(:dateTo   AS date)   IS NULL OR i.issue_date <= CAST(:dateTo   AS date))
          AND (CAST(:includeExcluded AS boolean) = TRUE OR i.excluded_at IS NULL)
    """, nativeQuery = true
    )
    fun findWithFilters(
        @Param("studioId") studioId: UUID,
        @Param("source") source: String?,
        @Param("ksefStatus") ksefStatus: String?,
        @Param("duplicateStatus") duplicateStatus: String?,
        @Param("dateFrom") dateFrom: LocalDate?,
        @Param("dateTo") dateTo: LocalDate?,
        @Param("includeExcluded") includeExcluded: Boolean,
        pageable: Pageable
    ): Page<KsefRevenueInvoiceEntity>

    // ── Statystyki ─────────────────────────────────────────────────────────────

    /**
     * Miesięczny rozkład przychodów. Liczą się faktury istniejące w KSeF
     * (ACCEPTED — dla CRM i EXTERNAL) oraz będące w drodze (SUBMITTED/QUEUED_RETRY,
     * wystawione w trybie offline24 — prawnie wystawione). Wyklucza REJECTED
     * i potwierdzone duplikaty oraz faktury ukryte ręcznie ze statystyk.
     * Korekty (KOR) liczone osobno — kwoty korekt niosą znak.
     *
     * Kolumny: month_label, gross, net, vat, invoice_count, correction_count, external_count
     */
    @Query(
        value = """
        SELECT
            TO_CHAR(DATE_TRUNC('month', i.issue_date), 'YYYY-MM')       AS month_label,
            COALESCE(SUM(i.total_gross), 0)                             AS gross,
            COALESCE(SUM(i.total_net),   0)                             AS net,
            COALESCE(SUM(i.total_vat),   0)                             AS vat,
            COUNT(CASE WHEN i.invoice_type = 'VAT' THEN 1 END)          AS invoice_count,
            COUNT(CASE WHEN i.invoice_type = 'KOR' THEN 1 END)          AS correction_count,
            COUNT(CASE WHEN i.source = 'EXTERNAL' THEN 1 END)           AS external_count
        FROM ksef_revenue_invoices i
        WHERE i.studio_id = :studioId
          AND i.ksef_status IN ('ACCEPTED', 'SUBMITTED', 'QUEUED_RETRY', 'SENDING', 'PENDING')
          AND i.duplicate_status <> 'CONFIRMED_DUPLICATE'
          AND i.excluded_at IS NULL
          AND i.issue_date >= CAST(:dateFrom AS date)
          AND i.issue_date <  CAST(:dateTo   AS date)
        GROUP BY DATE_TRUNC('month', i.issue_date)
        ORDER BY month_label ASC
    """, nativeQuery = true
    )
    fun findMonthlyStatistics(
        @Param("studioId") studioId: UUID,
        @Param("dateFrom") dateFrom: LocalDate,
        @Param("dateTo") dateTo: LocalDate
    ): List<Array<Any?>>

    /** Suma roczna — te same filtry co [findMonthlyStatistics]. */
    @Query(
        value = """
        SELECT
            COALESCE(SUM(i.total_gross), 0)                             AS gross,
            COALESCE(SUM(i.total_net),   0)                             AS net,
            COALESCE(SUM(i.total_vat),   0)                             AS vat,
            COUNT(CASE WHEN i.invoice_type = 'VAT' THEN 1 END)          AS invoice_count,
            COUNT(CASE WHEN i.invoice_type = 'KOR' THEN 1 END)          AS correction_count,
            COUNT(CASE WHEN i.source = 'EXTERNAL' THEN 1 END)           AS external_count
        FROM ksef_revenue_invoices i
        WHERE i.studio_id = :studioId
          AND i.ksef_status IN ('ACCEPTED', 'SUBMITTED', 'QUEUED_RETRY', 'SENDING', 'PENDING')
          AND i.duplicate_status <> 'CONFIRMED_DUPLICATE'
          AND i.excluded_at IS NULL
          AND i.issue_date >= CAST(:dateFrom AS date)
          AND i.issue_date <  CAST(:dateTo   AS date)
    """, nativeQuery = true
    )
    fun findTotalStatistics(
        @Param("studioId") studioId: UUID,
        @Param("dateFrom") dateFrom: LocalDate,
        @Param("dateTo") dateTo: LocalDate
    ): Array<Any?>

    /**
     * Suma brutto faktur przychodowych o danym statusie płatności — podstawa kafla
     * „Przychody" i „Należności" w module finansów.
     *
     * Korekty (KOR) niosą kwoty ze znakiem, więc korekta do zera realnie zeruje
     * przychód z faktury pierwotnej. Filtry statusów są te same co w statystykach:
     * liczy się dokument istniejący w KSeF lub będący w drodze (offline24),
     * z pominięciem odrzuconych, potwierdzonych duplikatów i faktur ukrytych ręcznie.
     *
     * Zakres dat po issue_date — jak w [FinancialDocumentRepository.sumGross],
     * żeby obie strony sumy mówiły o tym samym okresie.
     */
    @Query(
        value = """
        SELECT COALESCE(SUM(i.total_gross), 0)
        FROM ksef_revenue_invoices i
        WHERE i.studio_id = :studioId
          AND i.payment_status = CAST(:paymentStatus AS text)
          AND i.ksef_status IN ('ACCEPTED', 'SUBMITTED', 'QUEUED_RETRY', 'SENDING', 'PENDING')
          AND i.duplicate_status <> 'CONFIRMED_DUPLICATE'
          AND i.excluded_at IS NULL
          AND (CAST(:dateFrom AS date) IS NULL OR i.issue_date >= CAST(:dateFrom AS date))
          AND (CAST(:dateTo   AS date) IS NULL OR i.issue_date <= CAST(:dateTo   AS date))
    """, nativeQuery = true
    )
    fun sumGrossByPaymentStatus(
        @Param("studioId") studioId: UUID,
        @Param("paymentStatus") paymentStatus: String,
        @Param("dateFrom") dateFrom: LocalDate?,
        @Param("dateTo") dateTo: LocalDate?
    ): Long

    fun countByStudioIdAndSourceAndKsefStatus(
        studioId: UUID,
        source: RevenueSource,
        ksefStatus: KsefRevenueStatus
    ): Long

    fun countByStudioIdAndKsefStatusIn(studioId: UUID, statuses: List<KsefRevenueStatus>): Long
}
