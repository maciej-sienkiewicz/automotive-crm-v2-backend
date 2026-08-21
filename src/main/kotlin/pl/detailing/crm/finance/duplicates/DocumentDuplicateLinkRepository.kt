package pl.detailing.crm.finance.duplicates

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

/**
 * Kandydaci na parę zdublowanych dokumentów — reguła S1.
 *
 * S1: identyczna trójka kwot (netto, VAT, brutto) co do grosza oraz data
 * wystawienia w oknie ±[DocumentDuplicateDetector.MATCH_WINDOW_DAYS] dni.
 * Świadomie nie porównujemy nabywcy: w praktyce ta sama sprzedaż bywa
 * fakturowana na inny podmiot niż ten z CRM-a (osoba prywatna w CRM-ie,
 * firma współmałżonka na fakturze księgowej), więc zgodność stron jest
 * sygnałem zawodnym, a zgodność trzech kwot i daty — mocnym.
 *
 * Dokument, który występuje w JAKIMKOLWIEK powiązaniu (również odrzuconym),
 * jest z dopasowywania wyłączony. To celowo zawężone: raz rozstrzygnięty
 * dokument nie wraca, więc statystyki nie potrafią się „zakołysać" między
 * kolejnymi przebiegami.
 *
 * Kwoty wszędzie w groszach — po V80 dotyczy to również faktur kosztowych,
 * bez czego porównanie „co do grosza" nie miałoby sensu na liczbach
 * zmiennoprzecinkowych.
 */
@Repository
interface DocumentDuplicateLinkRepository : JpaRepository<DocumentDuplicateLinkEntity, UUID> {

    fun findByStudioIdAndDismissedAtIsNullOrderByDetectedAtDesc(studioId: UUID): List<DocumentDuplicateLinkEntity>

    fun findByIdAndStudioId(id: UUID, studioId: UUID): DocumentDuplicateLinkEntity?

    /**
     * Faktura sprzedażowa wystawiona POZA CRM-em kontra dokument finansowy
     * o tej samej kwocie. Wygrywa faktura (istnieje w KSeF), przegrywa dokument
     * finansowy. To jest przypadek „paragon w CRM-ie, faktura u księgowej".
     *
     * Celowo tylko source = EXTERNAL. Faktura wystawiona w CRM-ie ma już swój
     * dokument finansowy powiązany przez ksef_revenue_invoice_id, więc nie ma
     * czego dopasowywać — a dopuszczenie jej do gry oznaczałoby, że faktura dla
     * klienta A wycisza niepowiązany paragon dla klienta B na tę samą kwotę
     * z tego samego dnia. Podwójne dokumentowanie w obrębie samego CRM-a
     * zostaje poza zasięgiem automatu: jest widoczne dla użytkownika i nie warte
     * ryzyka zaniżenia przychodu.
     *
     * Dokumenty finansowe powiązane z fakturą przez ksef_revenue_invoice_id są
     * pominięte: one już nie liczą się do sum, więc nie ma czego wykluczać.
     *
     * Kolumny wyniku: winner_id, loser_id, total_gross, issue_date, day_gap
     */
    @Query(
        value = """
        SELECT
            r.id            AS winner_id,
            d.id            AS loser_id,
            r.total_gross   AS total_gross,
            r.issue_date    AS issue_date,
            ABS(d.issue_date - r.issue_date) AS day_gap
        FROM ksef_revenue_invoices r
        JOIN financial_documents d
          ON  d.studio_id   = r.studio_id
          AND d.direction   = 'INCOME'
          AND d.deleted_at  IS NULL
          AND d.excluded_at IS NULL
          AND d.ksef_revenue_invoice_id IS NULL
          AND d.total_net   = r.total_net
          AND d.total_vat   = r.total_vat
          AND d.total_gross = r.total_gross
          AND d.issue_date BETWEEN r.issue_date - CAST(:windowDays AS int)
                               AND r.issue_date + CAST(:windowDays AS int)
        WHERE r.studio_id   = :studioId
          AND r.source      = 'EXTERNAL'
          AND r.ksef_number IS NOT NULL
          AND r.invoice_type <> 'KOR'
          AND r.excluded_at IS NULL
          AND r.duplicate_status <> 'CONFIRMED_DUPLICATE'
          AND r.total_gross <> 0
          AND r.issue_date BETWEEN :dateFrom AND :dateTo
          AND NOT EXISTS (
              SELECT 1 FROM document_duplicate_links l
              WHERE l.studio_id = r.studio_id
                AND ((l.winner_kind = 'KSEF_REVENUE' AND l.winner_id = r.id)
                  OR (l.loser_kind  = 'KSEF_REVENUE' AND l.loser_id  = r.id))
          )
          AND NOT EXISTS (
              SELECT 1 FROM document_duplicate_links l
              WHERE l.studio_id = d.studio_id
                AND ((l.winner_kind = 'FINANCIAL_DOCUMENT' AND l.winner_id = d.id)
                  OR (l.loser_kind  = 'FINANCIAL_DOCUMENT' AND l.loser_id  = d.id))
          )
        ORDER BY r.issue_date, r.id, day_gap, d.id
    """, nativeQuery = true
    )
    fun findRevenueVsFinancialCandidates(
        @Param("studioId") studioId: UUID,
        @Param("dateFrom") dateFrom: LocalDate,
        @Param("dateTo") dateTo: LocalDate,
        @Param("windowDays") windowDays: Int
    ): List<Array<Any?>>

    /**
     * Faktura kosztowa z KSeF kontra dokument finansowy kierunku EXPENSE.
     *
     * Przypadek lustrzany: właściciel wpisał koszt ręcznie w module finansów,
     * a ta sama faktura przyszła potem pullem z KSeF.
     *
     * issue_date faktury kosztowej bywa puste (metadane bez daty wystawienia),
     * więc bierzemy datę wystawienia albo dzień rejestracji w KSeF.
     */
    @Query(
        value = """
        SELECT
            k.id            AS winner_id,
            d.id            AS loser_id,
            k.gross_amount  AS total_gross,
            COALESCE(k.issue_date, CAST(k.invoicing_date AS date)) AS issue_date,
            ABS(d.issue_date - COALESCE(k.issue_date, CAST(k.invoicing_date AS date))) AS day_gap
        FROM ksef_invoices k
        JOIN financial_documents d
          ON  d.studio_id   = k.studio_id
          AND d.direction   = 'EXPENSE'
          AND d.deleted_at  IS NULL
          AND d.excluded_at IS NULL
          AND d.total_net   = k.net_amount
          AND d.total_vat   = k.vat_amount
          AND d.total_gross = k.gross_amount
          AND d.issue_date BETWEEN COALESCE(k.issue_date, CAST(k.invoicing_date AS date)) - CAST(:windowDays AS int)
                               AND COALESCE(k.issue_date, CAST(k.invoicing_date AS date)) + CAST(:windowDays AS int)
        WHERE k.studio_id = :studioId
          AND k.source    = 'KSEF'
          AND k.status    NOT IN ('CANCELLED', 'EXCLUDED')
          AND k.is_correction = FALSE
          AND k.gross_amount IS NOT NULL
          AND k.gross_amount <> 0
          AND COALESCE(k.issue_date, CAST(k.invoicing_date AS date)) BETWEEN :dateFrom AND :dateTo
          AND NOT EXISTS (
              SELECT 1 FROM document_duplicate_links l
              WHERE l.studio_id = k.studio_id
                AND ((l.winner_kind = 'KSEF_EXPENSE' AND l.winner_id = k.id)
                  OR (l.loser_kind  = 'KSEF_EXPENSE' AND l.loser_id  = k.id))
          )
          AND NOT EXISTS (
              SELECT 1 FROM document_duplicate_links l
              WHERE l.studio_id = d.studio_id
                AND ((l.winner_kind = 'FINANCIAL_DOCUMENT' AND l.winner_id = d.id)
                  OR (l.loser_kind  = 'FINANCIAL_DOCUMENT' AND l.loser_id  = d.id))
          )
        ORDER BY issue_date, k.id, day_gap, d.id
    """, nativeQuery = true
    )
    fun findExpenseVsFinancialCandidates(
        @Param("studioId") studioId: UUID,
        @Param("dateFrom") dateFrom: LocalDate,
        @Param("dateTo") dateTo: LocalDate,
        @Param("windowDays") windowDays: Int
    ): List<Array<Any?>>

    /**
     * Ten sam koszt dwa razy w rejestrze kosztowym: raz wpisany ręcznie
     * (source = MANUAL, sztuczny numer MANUAL-…), raz pobrany z KSeF.
     *
     * Wygrywa wersja z KSeF — ma numer rejestru i komplet danych.
     */
    @Query(
        value = """
        SELECT
            k.id            AS winner_id,
            m.id            AS loser_id,
            k.gross_amount  AS total_gross,
            COALESCE(k.issue_date, CAST(k.invoicing_date AS date)) AS issue_date,
            ABS(COALESCE(m.issue_date, CAST(m.invoicing_date AS date))
                - COALESCE(k.issue_date, CAST(k.invoicing_date AS date))) AS day_gap
        FROM ksef_invoices k
        JOIN ksef_invoices m
          ON  m.studio_id    = k.studio_id
          AND m.source       = 'MANUAL'
          AND m.status       NOT IN ('CANCELLED', 'EXCLUDED')
          AND m.gross_amount = k.gross_amount
          AND m.net_amount   = k.net_amount
          AND COALESCE(m.issue_date, CAST(m.invoicing_date AS date))
              BETWEEN COALESCE(k.issue_date, CAST(k.invoicing_date AS date)) - CAST(:windowDays AS int)
                  AND COALESCE(k.issue_date, CAST(k.invoicing_date AS date)) + CAST(:windowDays AS int)
        WHERE k.studio_id = :studioId
          AND k.source    = 'KSEF'
          AND k.status    NOT IN ('CANCELLED', 'EXCLUDED')
          AND k.is_correction = FALSE
          AND k.gross_amount IS NOT NULL
          AND k.gross_amount <> 0
          AND COALESCE(k.issue_date, CAST(k.invoicing_date AS date)) BETWEEN :dateFrom AND :dateTo
          AND NOT EXISTS (
              SELECT 1 FROM document_duplicate_links l
              WHERE l.studio_id = k.studio_id
                AND ((l.winner_kind = 'KSEF_EXPENSE' AND l.winner_id = k.id)
                  OR (l.loser_kind  = 'KSEF_EXPENSE' AND l.loser_id  = k.id))
          )
          AND NOT EXISTS (
              SELECT 1 FROM document_duplicate_links l
              WHERE l.studio_id = m.studio_id
                AND ((l.winner_kind = 'KSEF_EXPENSE' AND l.winner_id = m.id)
                  OR (l.loser_kind  = 'KSEF_EXPENSE' AND l.loser_id  = m.id))
          )
        ORDER BY issue_date, k.id, day_gap, m.id
    """, nativeQuery = true
    )
    fun findExpenseKsefVsManualCandidates(
        @Param("studioId") studioId: UUID,
        @Param("dateFrom") dateFrom: LocalDate,
        @Param("dateTo") dateTo: LocalDate,
        @Param("windowDays") windowDays: Int
    ): List<Array<Any?>>
}
