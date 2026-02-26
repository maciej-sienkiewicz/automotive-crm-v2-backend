package pl.detailing.crm.ksef.sync

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.akmf.ksef.sdk.client.model.invoice.InvoiceQuerySubjectType
import pl.detailing.crm.ksef.fetch.FetchKsefInvoicesCommand
import pl.detailing.crm.ksef.fetch.FetchKsefInvoicesHandler
import pl.detailing.crm.shared.StudioId
import java.time.OffsetDateTime
import java.time.Period

/**
 * Zarządza delta synchronizacją faktur KSeF dla jednego studia.
 *
 * Strategia:
 * - Pierwsze uruchomienie: pobiera faktury z ostatnich [INITIAL_LOOKBACK_MONTHS] miesięcy
 * - Kolejne uruchomienia: pobiera od (lastSync - [SYNC_OVERLAP_HOURS]h) do teraz
 *   Nakładka 1h zabezpiecza przed przegapieniem faktur z opóźnieniami po stronie KSeF
 * - Zawsze pobierane są zarówno INCOME (SUBJECT1) jak i EXPENSE (SUBJECT2)
 * - W przypadku błędu kursor zapisuje status ERROR; następny run spróbuje ponownie
 *
 * WAŻNE: ta usługa działa poza kontekstem Spring Security (wywoływana przez scheduler),
 * dlatego nie używa SecurityContextHelper.
 */
@Service
class KsefSyncService(
    private val fetchHandler: FetchKsefInvoicesHandler,
    private val cursorRepository: KsefSyncCursorRepository
) {
    private val log = LoggerFactory.getLogger(KsefSyncService::class.java)

    companion object {
        private val INITIAL_LOOKBACK_MONTHS = Period.ofMonths(3)
        private const val SYNC_OVERLAP_HOURS = 1L
        private const val PAGE_SIZE = 100
    }

    /**
     * Synchronizuje faktury dla jednego studia.
     * Bezpieczne do wywołania równoległego – sprawdza status RUNNING przed startem.
     */
    @Transactional
    fun syncStudio(studioId: StudioId) {
        val cursor = cursorRepository.findById(studioId.value)
            .orElse(KsefSyncCursorEntity(studioId = studioId.value))

        // Pomijamy jeśli poprzedni sync wciąż trwa (ochrona przed równoległymi wywołaniami)
        if (cursor.syncStatus == "RUNNING") {
            log.warn("KSeF sync already RUNNING for studio={}, skipping this run", studioId)
            return
        }

        cursorRepository.save(cursor.toRunning())

        val now = OffsetDateTime.now()

        try {
            val incomeFrom = incomeFrom(cursor, now)
            val expenseFrom = expenseFrom(cursor, now)

            log.info(
                "KSeF sync started studio={} | INCOME from={} | EXPENSE from={}",
                studioId, incomeFrom, expenseFrom
            )

            // Pobierz przychody (studio jest sprzedawcą)
            val incomeResult = fetchHandler.handle(
                FetchKsefInvoicesCommand(
                    studioId = studioId,
                    dateFrom = incomeFrom,
                    dateTo = now,
                    subjectType = InvoiceQuerySubjectType.SUBJECT1,
                    pageSize = PAGE_SIZE
                )
            )

            // Pobierz koszty (studio jest nabywcą)
            val expenseResult = fetchHandler.handle(
                FetchKsefInvoicesCommand(
                    studioId = studioId,
                    dateFrom = expenseFrom,
                    dateTo = now,
                    subjectType = InvoiceQuerySubjectType.SUBJECT2,
                    pageSize = PAGE_SIZE
                )
            )

            cursorRepository.save(cursor.toSuccess(now))

            log.info(
                "KSeF sync completed studio={} | income: fetched={} skipped={} | expense: fetched={} skipped={}",
                studioId,
                incomeResult.fetched, incomeResult.skipped,
                expenseResult.fetched, expenseResult.skipped
            )
        } catch (e: Exception) {
            log.error("KSeF sync FAILED for studio={}: {}", studioId, e.message, e)
            cursorRepository.save(cursor.toError(e.message ?: "Unknown error"))
        }
    }

    private fun incomeFrom(cursor: KsefSyncCursorEntity, now: OffsetDateTime): OffsetDateTime =
        (cursor.lastIncomeSync ?: now.minus(INITIAL_LOOKBACK_MONTHS))
            .minusHours(SYNC_OVERLAP_HOURS)

    private fun expenseFrom(cursor: KsefSyncCursorEntity, now: OffsetDateTime): OffsetDateTime =
        (cursor.lastExpenseSync ?: now.minus(INITIAL_LOOKBACK_MONTHS))
            .minusHours(SYNC_OVERLAP_HOURS)
}
