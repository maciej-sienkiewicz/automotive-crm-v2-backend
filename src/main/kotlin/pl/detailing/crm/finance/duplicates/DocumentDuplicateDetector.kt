package pl.detailing.crm.finance.duplicates

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.finance.infrastructure.FinancialDocumentRepository
import pl.detailing.crm.ksef.infrastructure.KsefInvoiceRepository
import pl.detailing.crm.ksef.revenue.infrastructure.KsefRevenueInvoiceRepository
import pl.detailing.crm.shared.StudioId
import java.sql.Date
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Wykrywa dokumenty opisujące tę samą sprzedaż albo ten sam zakup dwa razy
 * i wycisza ten o mniejszej mocy dowodowej.
 *
 * Skąd się biorą takie pary: studio wystawia część faktur w CRM-ie, a część
 * powstaje w programie księgowym. Obie trafiają do KSeF jako osobne dokumenty,
 * a w CRM-ie po jednej stronie zostaje paragon albo ręcznie wpisany koszt.
 * Sumowanie obu stron zawyżało przychód i koszty.
 *
 * Zasady, na których to stoi:
 *
 *  - **Nic nie czeka na decyzję użytkownika.** Wykrycie od razu wycisza
 *    dokument przegrywający; właściciel dostaje gotowy wynik, nie zadanie.
 *  - **Wygrywa dokument o wyższej mocy dowodowej** — faktura obecna w KSeF
 *    bije dokument spoza KSeF, wersja z KSeF bije wpis ręczny. Reguła jest
 *    stała, nie ma czego konfigurować.
 *  - **Przy wielu kandydatach parujemy z najbliższym datą.** Trzy paragony
 *    po 150 zł w tygodniu i jedna faktura zewnętrzna 150 zł: wyciszenie
 *    jednego paragonu daje poprawną sumę, brak parowania zawyża ją o 150 zł.
 *    Niejednoznaczność przypisania nie jest niejednoznacznością kwoty.
 *  - **Raz rozstrzygnięta para nie wraca.** Odrzucenie („to dwie różne
 *    sprzedaże") cofa wyciszenie i trafia na czarną listę.
 *
 * Skutek zapisujemy w mechanizmie, który już istnieje w każdym rejestrze —
 * `excluded_at`, a dla faktur kosztowych `status = 'EXCLUDED'` — więc żadne
 * zapytanie raportowe nie wymagało zmiany.
 */
@Service
class DocumentDuplicateDetector(
    private val linkRepository: DocumentDuplicateLinkRepository,
    private val financialDocumentRepository: FinancialDocumentRepository,
    private val revenueInvoiceRepository: KsefRevenueInvoiceRepository,
    private val expenseInvoiceRepository: KsefInvoiceRepository
) {
    private val log = LoggerFactory.getLogger(DocumentDuplicateDetector::class.java)

    companion object {
        /** Okno dopasowania daty wystawienia, w dniach w każdą stronę. */
        const val MATCH_WINDOW_DAYS = 3

        /**
         * Jak głęboko wstecz skanujemy przy każdym przebiegu. Pull z KSeF potrafi
         * przynieść fakturę wystawioną kilkanaście dni wcześniej, więc okno musi
         * sięgać dalej niż jeden cykl synchronizacji. Starsze okresy zostają
         * nietknięte, żeby zamknięty miesiąc nie zmieniał się pod ręką.
         */
        const val SCAN_WINDOW_DAYS = 45L

        /** Sprawca zmiany, gdy wyciszenie pochodzi od systemu, a nie od człowieka. */
        val SYSTEM_USER_ID: UUID = UUID(0L, 0L)
    }

    /** Para do zapisania: kto wygrywa, kto zostaje wyciszony i na jakiej podstawie. */
    private data class Candidate(
        val winnerKind: DocumentKind,
        val winnerId: UUID,
        val loserKind: DocumentKind,
        val loserId: UUID,
        val totalGross: Long,
        val issueDate: LocalDate
    )

    /**
     * Przebiega okno ostatnich [SCAN_WINDOW_DAYS] dni i wycisza znalezione duplikaty.
     * Wołane po synchronizacji z KSeF — obie strony pary są wtedy już w bazie.
     *
     * @return liczba nowo wyciszonych dokumentów
     */
    @Transactional
    fun scanRecent(studioId: StudioId, today: LocalDate = LocalDate.now()): Int {
        val dateFrom = today.minusDays(SCAN_WINDOW_DAYS)
        val candidates =
            read(linkRepository.findRevenueVsFinancialCandidates(studioId.value, dateFrom, today, MATCH_WINDOW_DAYS),
                DocumentKind.KSEF_REVENUE, DocumentKind.FINANCIAL_DOCUMENT) +
            read(linkRepository.findExpenseVsFinancialCandidates(studioId.value, dateFrom, today, MATCH_WINDOW_DAYS),
                DocumentKind.KSEF_EXPENSE, DocumentKind.FINANCIAL_DOCUMENT) +
            read(linkRepository.findExpenseKsefVsManualCandidates(studioId.value, dateFrom, today, MATCH_WINDOW_DAYS),
                DocumentKind.KSEF_EXPENSE, DocumentKind.KSEF_EXPENSE)

        // Zapytania zwracają kandydatów posortowanych od najlepszego dopasowania;
        // tutaj pilnujemy tylko, żeby jeden dokument nie wszedł do dwóch par
        // w ramach tego samego przebiegu. Kardynalności między przebiegami
        // pilnują indeksy częściowe na tabeli powiązań.
        val usedWinners = mutableSetOf<Pair<DocumentKind, UUID>>()
        val usedLosers = mutableSetOf<Pair<DocumentKind, UUID>>()
        var linked = 0

        for (candidate in candidates) {
            val winnerKey = candidate.winnerKind to candidate.winnerId
            val loserKey = candidate.loserKind to candidate.loserId
            if (winnerKey == loserKey) continue
            if (!usedWinners.add(winnerKey)) continue
            if (!usedLosers.add(loserKey)) {
                usedWinners.remove(winnerKey)
                continue
            }

            if (!suppress(studioId, candidate.loserKind, candidate.loserId)) {
                usedWinners.remove(winnerKey)
                usedLosers.remove(loserKey)
                continue
            }

            linkRepository.save(
                DocumentDuplicateLinkEntity(
                    studioId = studioId.value,
                    winnerKind = candidate.winnerKind,
                    winnerId = candidate.winnerId,
                    loserKind = candidate.loserKind,
                    loserId = candidate.loserId,
                    totalGross = candidate.totalGross,
                    issueDate = candidate.issueDate
                )
            )
            linked++

            log.info(
                "Zdublowany dokument: {} {} wycisza {} {} — {} gr, {}",
                candidate.winnerKind, candidate.winnerId,
                candidate.loserKind, candidate.loserId,
                candidate.totalGross, candidate.issueDate
            )
        }

        if (linked > 0) log.info("Detekcja duplikatów studio={}: wyciszono {}", studioId, linked)
        return linked
    }

    /**
     * „To jednak dwie różne sprzedaże" — cofa wyciszenie i zamyka temat pary
     * na stałe. Jedyne miejsce, w którym człowiek w ogóle musi się pojawić,
     * i wyłącznie wtedy, gdy automat się pomylił.
     */
    @Transactional
    fun dismiss(studioId: StudioId, linkId: UUID, userId: UUID): Boolean {
        val link = linkRepository.findByIdAndStudioId(linkId, studioId.value) ?: return false
        if (link.isDismissed) return true

        restore(studioId, link.loserKind, link.loserId, userId)
        link.dismissedAt = Instant.now()
        link.dismissedBy = userId
        linkRepository.save(link)
        return true
    }

    // ── Private ────────────────────────────────────────────────────────────────

    private fun read(rows: List<Array<Any?>>, winnerKind: DocumentKind, loserKind: DocumentKind): List<Candidate> =
        rows.mapNotNull { row ->
            val winnerId = row[0].asUuid() ?: return@mapNotNull null
            val loserId = row[1].asUuid() ?: return@mapNotNull null
            Candidate(
                winnerKind = winnerKind,
                winnerId = winnerId,
                loserKind = loserKind,
                loserId = loserId,
                totalGross = (row[2] as? Number)?.toLong() ?: 0L,
                issueDate = row[3].asLocalDate() ?: return@mapNotNull null
            )
        }

    /** @return false, gdy dokumentu nie da się wyciszyć (zniknął albo już jest ukryty) */
    private fun suppress(studioId: StudioId, kind: DocumentKind, id: UUID): Boolean = when (kind) {
        DocumentKind.FINANCIAL_DOCUMENT -> {
            val document = financialDocumentRepository.findById(id).orElse(null)
            if (document == null || document.studioId != studioId.value || document.isExcluded) false
            else {
                document.markExcluded(SYSTEM_USER_ID)
                financialDocumentRepository.save(document)
                true
            }
        }

        DocumentKind.KSEF_REVENUE -> {
            val invoice = revenueInvoiceRepository.findByIdAndStudioId(id, studioId.value)
            if (invoice == null || invoice.excludedAt != null) false
            else {
                invoice.excludedAt = Instant.now()
                invoice.excludedBy = SYSTEM_USER_ID
                revenueInvoiceRepository.save(invoice)
                true
            }
        }

        DocumentKind.KSEF_EXPENSE -> {
            val invoice = expenseInvoiceRepository.findById(id).orElse(null)
            if (invoice == null || invoice.studioId != studioId.value || invoice.status == "EXCLUDED") false
            else {
                expenseInvoiceRepository.updateStatus(studioId.value, invoice.ksefNumber, "EXCLUDED")
                true
            }
        }
    }

    /** Cofa wyciszenie założone przez [suppress]. */
    private fun restore(studioId: StudioId, kind: DocumentKind, id: UUID, userId: UUID) {
        when (kind) {
            DocumentKind.FINANCIAL_DOCUMENT -> {
                val document = financialDocumentRepository.findById(id).orElse(null)
                if (document != null && document.studioId == studioId.value) {
                    document.markRestored(userId)
                    financialDocumentRepository.save(document)
                }
            }

            DocumentKind.KSEF_REVENUE -> {
                val invoice = revenueInvoiceRepository.findByIdAndStudioId(id, studioId.value)
                if (invoice != null) {
                    invoice.excludedAt = null
                    invoice.excludedBy = null
                    revenueInvoiceRepository.save(invoice)
                }
            }

            DocumentKind.KSEF_EXPENSE -> {
                val invoice = expenseInvoiceRepository.findById(id).orElse(null)
                if (invoice != null && invoice.studioId == studioId.value) {
                    expenseInvoiceRepository.updateStatus(studioId.value, invoice.ksefNumber, "ACTIVE")
                }
            }
        }
    }

    private fun Any?.asUuid(): UUID? = when (this) {
        is UUID -> this
        is String -> runCatching { UUID.fromString(this) }.getOrNull()
        else -> null
    }

    private fun Any?.asLocalDate(): LocalDate? = when (this) {
        is LocalDate -> this
        is Date -> toLocalDate()
        is java.time.temporal.TemporalAccessor -> runCatching { LocalDate.from(this) }.getOrNull()
        else -> null
    }
}
