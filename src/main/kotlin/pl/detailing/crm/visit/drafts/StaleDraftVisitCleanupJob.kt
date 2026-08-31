package pl.detailing.crm.visit.drafts

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.visit.transitions.cancel.CancelDraftVisitCommand
import pl.detailing.crm.visit.transitions.cancel.CancelDraftVisitHandler
import java.time.Duration
import java.time.Instant

/**
 * Domyka porzucone przyjęcia pojazdu.
 *
 * ## Dlaczego to musi istnieć
 *
 * Szkic wizyty (DRAFT) jest stanem przejściowym kreatora przyjęcia, ale nic dotąd nie
 * pilnowało, żeby faktycznie przeszedł: wystarczyło zamknąć okno z dokumentami i wizyta
 * zostawała w tym stanie na zawsze. Rekord bez ścieżki wyjścia to nie tylko śmieć w
 * bazie — to zajęty numer VIS-, komplet protokołów i pliki w S3, rezerwacja, której nie
 * da się ponownie przyjąć bez tworzenia drugiej wizyty, i wiersz, który przy każdej
 * zmianie zasad widoczności grozi wyciekiem do interfejsu.
 *
 * ## Czego to zadanie NIE robi
 *
 * Nie jest podstawową ścieżką sprzątania — jest ostatnią. Pierwszą jest widoczna
 * kolejka nieukończonych przyjęć ([OpenDraftVisitService]) i okno, które nie pozwala
 * wyjść z przyjęcia bez decyzji. Zadanie zabiera się do szkicu dopiero po
 * [DraftVisitProperties.expireAfterHours] godzinach, kiedy jest już jasne, że nikt do
 * niego nie wróci.
 *
 * Anulowanie idzie tą samą drogą co ręczne ([CancelDraftVisitHandler]): usuwa protokoły,
 * dokumenty, wpisy dziennika i pliki z S3, zostawia rezerwację w CONFIRMED (auto można
 * przyjąć od nowa) i zapisuje w dzienniku wpis z aktorem systemowym oraz powodem.
 */
@Component
class StaleDraftVisitCleanupJob(
    private val visitRepository: VisitRepository,
    private val cancelDraftVisitHandler: CancelDraftVisitHandler,
    private val properties: DraftVisitProperties
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "\${crm.visits.drafts.cleanup-cron:0 50 3 * * *}")
    fun cancelAbandonedDrafts() {
        if (!properties.cleanupEnabled) return

        val threshold = Instant.now().minus(Duration.ofHours(properties.expireAfterHours))
        val abandoned = visitRepository.findDraftsCreatedBefore(
            threshold = threshold,
            pageable = PageRequest.of(0, properties.cleanupBatchSize)
        )
        if (abandoned.isEmpty()) return

        var cancelled = 0
        abandoned.forEach { draft ->
            // Jeden szkic nie może przewrócić przebiegu: każdy ma własne pliki w S3 i
            // własny graf encji, a błąd na jednym nie mówi nic o pozostałych.
            try {
                runBlocking {
                    cancelDraftVisitHandler.handle(CancelDraftVisitCommand(
                        visitId = VisitId(draft.id),
                        studioId = StudioId(draft.studioId),
                        userId = null,
                        userName = "System (automatyczne wygaśnięcie)",
                        reason = "Przyjęcie pojazdu nie zostało dokończone przez " +
                            "${properties.expireAfterHours} h"
                    ))
                }
                cancelled++
            } catch (e: Exception) {
                logger.error(
                    "Nie udało się anulować porzuconego szkicu wizyty {} (studio {}): {}",
                    draft.id, draft.studioId, e.message, e
                )
            }
        }

        logger.info(
            "Porzucone przyjęcia: anulowano {} z {} szkiców starszych niż {} h",
            cancelled, abandoned.size, properties.expireAfterHours
        )
    }
}
