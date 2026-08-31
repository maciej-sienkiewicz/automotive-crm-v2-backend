package pl.detailing.crm.visit.infrastructure

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.feed.FeedVisitVisibility
import java.util.UUID

/**
 * Aktywność pomija wszystko, co dzieje się wokół wizyty w statusie DRAFT.
 *
 * Zapytanie idzie po `(studio_id, status)` i zwraca wyłącznie identyfikatory otwartych
 * szkiców — zbiór z natury mały, bo szkic żyje od zapisu wizyty do jej zatwierdzenia
 * lub anulowania (a te zapomniane domyka
 * [pl.detailing.crm.visit.drafts.StaleDraftVisitCleanupJob]). Lista trafia do zapytania
 * feedu jako wykluczenie, a nie do filtrowania wyniku w pamięci: strona keysetowa musi
 * oddać tyle wierszy, ile obiecuje kursorowi.
 */
@Component
class DraftVisitFeedVisibility(
    private val visitRepository: VisitRepository
) : FeedVisitVisibility {

    @Transactional(readOnly = true)
    override fun hiddenVisitIds(studioId: UUID): List<UUID> =
        visitRepository.findOpenDraftIds(studioId)
}
