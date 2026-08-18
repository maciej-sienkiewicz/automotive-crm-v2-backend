package pl.detailing.crm.leads.update

import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.comms.domain.CommOutboundSentEvent
import pl.detailing.crm.leads.infrastructure.LeadRepository
import java.time.Instant

/**
 * Stamps the lead's first response time when the first reply leaves its thread.
 * Kept as an event listener so the comms module never has to know what a lead is.
 */
@Component
class LeadFirstResponseListener(
    private val leadRepository: LeadRepository
) {

    @EventListener
    @Transactional
    fun onOutboundSent(event: CommOutboundSentEvent) {
        val lead = leadRepository.findByThreadId(event.threadId) ?: return
        if (lead.firstResponseAt != null) return
        lead.firstResponseAt = event.sentAt
        lead.updatedAt = Instant.now()
        leadRepository.save(lead)
    }
}
