package pl.detailing.crm.leads.tags.ai

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.tags.LeadTagCatalogService
import pl.detailing.crm.leads.update.LeadTagService
import pl.detailing.crm.shared.LeadChangedEvent
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.NewLeadCreatedEvent
import pl.detailing.crm.shared.StudioId

/**
 * Dopisuje nowemu leadowi tagi zapytania odczytane z jego treści.
 *
 * Podpięte pod [NewLeadCreatedEvent], a nie pod poszczególne handlery — to zdarzenie
 * publikują wszystkie cztery ścieżki powstawania leada (ręczne oznaczenie wątku, mail
 * z formularza, webhook formularza, tworzenie ręczne), więc jedno miejsce obsługuje je
 * wszystkie i żadna przyszła ścieżka nie wypadnie z tagowania przez przeoczenie. Ten
 * sam wybór i z tego samego powodu co w
 * [pl.detailing.crm.livemetrics.ingest.LeadMetricsListener].
 *
 * Po zatwierdzeniu transakcji i asynchronicznie: dobór tagów idzie do modelu i trwa
 * sekundy, a użytkownik ma dostać potwierdzenie utworzenia leada natychmiast. Gdyby
 * dobór zawiódł, lead po prostu nie ma tagów — to pole opisowe, nie warunek istnienia.
 *
 * WYBORU CZŁOWIEKA NIE NADPISUJEMY. Okno „Oznacz jako lead" pozwala zaznaczyć tagi
 * ręcznie i to zaznaczenie jest decyzją, a nie propozycją do poprawienia przez model.
 * Automat rusza wyłącznie wtedy, gdy lead nie ma jeszcze żadnego tagu.
 */
@Component
class LeadAutoTagListener(
    private val leadRepository: LeadRepository,
    private val tagService: LeadTagService,
    private val tagCatalog: LeadTagCatalogService,
    private val suggestionService: LeadTagSuggestionService,
    private val eventPublisher: ApplicationEventPublisher,
    @Value("\${crm.ai.lead-tags.enabled:true}") private val enabled: Boolean
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // REQUIRES_NEW, bo listener startuje PO zatwierdzeniu transakcji, która go wywołała —
    // nie ma już do czego dołączyć, a zapis tagów potrzebuje własnej transakcji.
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onNewLeadCreated(event: NewLeadCreatedEvent) {
        if (!enabled) return

        val lead = leadRepository.findById(event.leadId.value).orElse(null) ?: return

        // Tagi wybrane w oknie tworzenia leada są decyzją człowieka — kończymy.
        if (tagService.tagsOf(lead.id).isNotEmpty()) return

        val text = lead.initialMessage?.takeIf { it.isNotBlank() } ?: return
        val options = tagCatalog.listActive(StudioId(lead.studioId))
            .map { LeadTagOption(code = it.code, label = it.label) }
        if (options.isEmpty()) return

        val codes = runBlocking { suggestionService.suggest(text, options) }
        if (codes.isEmpty()) {
            log.debug("[LEAD_TAGS] Dla leada {} nie dobrano żadnego tagu", lead.id)
            return
        }

        // Ponowne sprawdzenie po rozmowie z modelem: przez te kilka sekund ktoś mógł
        // otworzyć leada i wybrać tagi ręcznie. Jego wybór jest ważniejszy.
        if (tagService.tagsOf(lead.id).isNotEmpty()) return

        tagService.replaceTags(lead.id, codes)
        log.info("[LEAD_TAGS] Lead {} otagowany automatycznie: {}", lead.id, codes.joinToString(", "))

        // Tabela leadów odświeża wiersz przez WebSocket — tagi pojawiają się bez F5.
        eventPublisher.publishEvent(
            LeadChangedEvent(source = this, studioId = StudioId(lead.studioId), leadId = LeadId(lead.id))
        )
    }
}
