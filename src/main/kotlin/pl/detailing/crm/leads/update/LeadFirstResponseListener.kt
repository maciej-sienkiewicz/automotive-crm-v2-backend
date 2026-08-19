package pl.detailing.crm.leads.update

import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.comms.domain.CommOutboundSentEvent
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.LeadStatus
import java.time.Instant

/**
 * Odpowiedź w wątku leada: stempluje czas pierwszej reakcji i przesuwa leada na
 * „W kontakcie".
 *
 * Status wynika z faktu, a nie z pamięci użytkownika: skoro odpisaliśmy, to lead
 * nie jest już „Nowy" i nikt nie musi tego klikać ręcznie. Przesuwamy wyłącznie
 * z NEW — leada zamkniętego, zarezerwowanego czy przegranego odpowiedź nie cofa
 * na wcześniejszy etap.
 *
 * Nasłuch zdarzenia, żeby moduł poczty nie musiał wiedzieć, czym jest lead.
 */
@Component
class LeadFirstResponseListener(
    private val leadRepository: LeadRepository,
    private val statusService: LeadStatusService
) {

    @EventListener
    @Transactional
    fun onOutboundSent(event: CommOutboundSentEvent) {
        val lead = leadRepository.findByThreadId(event.threadId) ?: return

        if (lead.firstResponseAt == null) {
            lead.firstResponseAt = event.sentAt
            lead.updatedAt = Instant.now()
            leadRepository.save(lead)
        }

        if (lead.status == LeadStatus.NEW) {
            statusService.transition(lead, LeadStatus.IN_PROGRESS)
        }
    }
}
