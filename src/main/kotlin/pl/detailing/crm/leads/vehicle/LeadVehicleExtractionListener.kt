package pl.detailing.crm.leads.vehicle

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionalEventListener
import pl.detailing.crm.comms.domain.CommDirection
import pl.detailing.crm.comms.infrastructure.CommMessageRepository
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.domain.LeadVehicleDetectionStatus
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.LeadChangedEvent
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.util.UUID

/**
 * Zdarzenie: lead powstał z wątku pocztowego i można poszukać w nim auta.
 * Osobny typ zamiast doklejania pola do NewLeadCreatedEvent — ten drugi jeździ po
 * całej aplikacji (powiadomienia, WebSocket) i nie ma powodu wiedzieć o LLM-ie.
 */
data class LeadThreadAttachedEvent(
    val leadId: UUID,
    val threadId: UUID
)

/**
 * Dopisuje leadowi markę i model auta odczytane z korespondencji.
 *
 * Po zatwierdzeniu transakcji i asynchronicznie: rozpoznanie idzie do LLM-a i trwa
 * sekundy, a użytkownik ma dostać potwierdzenie oznaczenia leada natychmiast.
 * Gdyby rozpoznanie zawiodło, lead po prostu nie ma marki — to pole opisowe,
 * nie warunek istnienia leada.
 */
@Component
class LeadVehicleExtractionListener(
    private val leadRepository: LeadRepository,
    private val messageRepository: CommMessageRepository,
    private val extractionService: LeadVehicleExtractionService,
    private val eventPublisher: ApplicationEventPublisher
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @TransactionalEventListener
    @Transactional
    fun onLeadThreadAttached(event: LeadThreadAttachedEvent) {
        val lead = leadRepository.findById(event.leadId).orElse(null) ?: return
        // Ręcznie wpisanej marki nie nadpisujemy — człowiek wie lepiej niż model.
        if (!lead.vehicleBrand.isNullOrBlank()) {
            finish(lead, brand = lead.vehicleBrand, model = lead.vehicleModel)
            return
        }

        val conversation = messageRepository.findByThreadIdOrderBySentAtAsc(event.threadId)
            .joinToString("\n\n") { message ->
                val who = if (message.direction == CommDirection.OUTBOUND) "Studio" else "Klient"
                // Tekst bez cytatów i stopek — inaczej model czyta w kółko tę samą
                // historię rozmowy i auta z cudzych podpisów.
                "$who: ${message.bodyTextClean.orEmpty()}"
            }
            .trim()

        // Cokolwiek się wydarzy — także brak treści i awaria modelu — lead musi wyjść
        // ze stanu PENDING. Inaczej w tabeli zostaje spinner, który kręci się na zawsze.
        val vehicle = if (conversation.isBlank()) null else runBlocking { extractionService.extract(conversation) }

        if (vehicle?.brand == null) {
            log.debug("[LEAD_VEHICLE] W wątku {} nie rozpoznano auta", event.threadId)
            finish(lead, brand = null, model = null)
            return
        }

        log.info("[LEAD_VEHICLE] Lead {} — rozpoznano {} {}", lead.id, vehicle.brand, vehicle.model ?: "")
        finish(lead, brand = vehicle.brand, model = vehicle.model)
    }

    /**
     * Zamyka rozpoznanie i budzi interfejs. Zdarzenie niesie pełny wiersz leada
     * WebSocketem, więc spinner w tabeli zamienia się w wynik bez odświeżania strony.
     */
    private fun finish(lead: LeadEntity, brand: String?, model: String?) {
        lead.vehicleBrand = brand
        lead.vehicleModel = model
        lead.vehicleDetectionStatus = LeadVehicleDetectionStatus.DONE
        lead.updatedAt = Instant.now()
        leadRepository.save(lead)

        eventPublisher.publishEvent(
            LeadChangedEvent(source = this, studioId = StudioId(lead.studioId), leadId = LeadId(lead.id))
        )
    }
}
