package pl.detailing.crm.leads.vehicle

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionalEventListener
import pl.detailing.crm.comms.domain.CommDirection
import pl.detailing.crm.comms.infrastructure.CommMessageRepository
import pl.detailing.crm.leads.infrastructure.LeadRepository
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
    private val extractionService: LeadVehicleExtractionService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @TransactionalEventListener
    @Transactional
    fun onLeadThreadAttached(event: LeadThreadAttachedEvent) {
        val lead = leadRepository.findById(event.leadId).orElse(null) ?: return
        // Ręcznie wpisanej marki nie nadpisujemy — człowiek wie lepiej niż model.
        if (!lead.vehicleBrand.isNullOrBlank()) return

        val conversation = messageRepository.findByThreadIdOrderBySentAtAsc(event.threadId)
            .joinToString("\n\n") { message ->
                val who = if (message.direction == CommDirection.OUTBOUND) "Studio" else "Klient"
                // Tekst bez cytatów i stopek — inaczej model czyta w kółko tę samą
                // historię rozmowy i auta z cudzych podpisów.
                "$who: ${message.bodyTextClean.orEmpty()}"
            }
            .trim()
        if (conversation.isBlank()) return

        val vehicle = runBlocking { extractionService.extract(conversation) }
        if (vehicle.brand == null) {
            log.debug("[LEAD_VEHICLE] W wątku {} nie rozpoznano auta", event.threadId)
            return
        }

        lead.vehicleBrand = vehicle.brand
        lead.vehicleModel = vehicle.model
        lead.updatedAt = Instant.now()
        leadRepository.save(lead)
        log.info("[LEAD_VEHICLE] Lead {} — rozpoznano {} {}", lead.id, vehicle.brand, vehicle.model ?: "")
    }
}
