package pl.detailing.crm.ksef.sync

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.akmf.ksef.sdk.client.model.invoice.InvoiceQuerySubjectType
import pl.detailing.crm.ksef.credentials.KsefCredentialsRepository
import pl.detailing.crm.ksef.fetch.FetchKsefInvoicesCommand
import pl.detailing.crm.ksef.fetch.FetchKsefInvoicesHandler
import pl.detailing.crm.shared.StudioId
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Zarządza delta synchronizacją faktur kosztowych KSeF dla jednego studia.
 *
 * Strategia:
 * - Pierwsze uruchomienie: pobiera faktury od momentu zapisu tokenu (credentials.createdAt).
 *   Gwarantuje brak danych historycznych sprzed aktywacji integracji.
 * - Kolejne uruchomienia: pobiera od (lastExpenseSync - [SYNC_OVERLAP_HOURS]h) do teraz.
 *   Nakładka 1h zabezpiecza przed przegapieniem faktur z opóźnieniami po stronie KSeF.
 * - Pobierane są WYŁĄCZNIE faktury kosztowe (SUBJECT2 – studio jest nabywcą).
 *   Faktury sprzedażowe (SUBJECT1) są ignorowane na poziomie zapytania do KSeF API.
 * - W przypadku błędu kursor zapisuje status ERROR; następny run spróbuje ponownie.
 * - Mechanizm stale-RUNNING: jeśli status = RUNNING przez >30 min (np. po restarcie serwera),
 *   kursor jest resetowany do IDLE i sync rusza od nowa.
 *
 * WAŻNE: ta usługa działa poza kontekstem Spring Security (wywoływana przez scheduler),
 * dlatego nie używa SecurityContextHelper.
 */
@Service
class KsefSyncService(
    private val fetchHandler: FetchKsefInvoicesHandler,
    private val cursorRepository: KsefSyncCursorRepository,
    private val credentialsRepository: KsefCredentialsRepository
) {
    private val log = LoggerFactory.getLogger(KsefSyncService::class.java)

    companion object {
        private const val SYNC_OVERLAP_HOURS = 1L
        private const val PAGE_SIZE = 100
        private val STALE_RUNNING_THRESHOLD = Duration.ofMinutes(30)
    }

    /**
     * Synchronizuje faktury kosztowe dla jednego studia.
     * Bezpieczne do wywołania równoległego – sprawdza status RUNNING przed startem.
     * Punkt zero dla pierwszego synca = credentials.createdAt (moment aktywacji integracji).
     */
    @Transactional
    fun syncStudio(studioId: StudioId) {
        val cursor = cursorRepository.findById(studioId.value)
            .orElse(KsefSyncCursorEntity(studioId = studioId.value))

        if (cursor.syncStatus == "RUNNING") {
            if (cursor.isStale(STALE_RUNNING_THRESHOLD)) {
                // Serwer mógł zostać zrestartowany w trakcie synca – resetujemy do IDLE
                // i kontynuujemy od ostatniego zapisanego punktu (bez cofania do historii)
                log.warn(
                    "KSeF sync RUNNING state is stale (>{}min) for studio={}, resetting to IDLE",
                    STALE_RUNNING_THRESHOLD.toMinutes(), studioId
                )
                cursorRepository.save(cursor.toIdle())
            } else {
                log.warn("KSeF sync already RUNNING for studio={}, skipping this run", studioId)
                return
            }
        }

        val credentials = credentialsRepository.findByStudioId(studioId.value) ?: run {
            log.warn("No KSeF credentials found for studio={}, skipping sync", studioId)
            return
        }

        // Punkt zero integracji – moment zapisu tokenu przez użytkownika.
        // Gwarantuje, że nigdy nie cofniemy się do danych sprzed aktywacji.
        val integrationStartedAt = credentials.createdAt.atOffset(ZoneOffset.UTC)

        cursorRepository.save(cursor.toRunning())

        val now = OffsetDateTime.now()

        try {
            val expenseFrom = expenseFrom(cursor, now, integrationStartedAt)

            log.info(
                "KSeF sync started studio={} | EXPENSE from={} (integrationStart={})",
                studioId, expenseFrom, integrationStartedAt
            )

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
                "KSeF sync completed studio={} | expense: fetched={} skipped={}",
                studioId, expenseResult.fetched, expenseResult.skipped
            )
        } catch (e: Exception) {
            log.error("KSeF sync FAILED for studio={}: {}", studioId, e.message, e)
            cursorRepository.save(cursor.toError(e.message ?: "Unknown error"))
        }
    }

    /**
     * Wyznacza datę startową dla fetchu faktur kosztowych.
     * - Pierwszy sync: integrationStartedAt (moment zapisu tokenu) – bez historii
     * - Kolejne synce: lastExpenseSync - SYNC_OVERLAP_HOURS, ale nigdy wcześniej niż integrationStartedAt
     */
    private fun expenseFrom(
        cursor: KsefSyncCursorEntity,
        now: OffsetDateTime,
        integrationStartedAt: OffsetDateTime
    ): OffsetDateTime {
        val base = cursor.lastExpenseSync
            ?.minusHours(SYNC_OVERLAP_HOURS)
            ?: integrationStartedAt

        // Dodatkowe zabezpieczenie: nigdy nie cofamy się przed moment aktywacji integracji
        return if (base.isBefore(integrationStartedAt)) integrationStartedAt else base
    }
}
