package pl.detailing.crm.livemetrics.ingest

import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import pl.detailing.crm.livemetrics.BusinessEventPublisher
import pl.detailing.crm.livemetrics.domain.BusinessEventType
import pl.detailing.crm.shared.NewLeadCreatedEvent

/**
 * Leady jako metryka — pojedynczy most zamiast wołania publishera w każdym miejscu tworzenia leada.
 *
 * Lead powstaje na czterech niezależnych ścieżkach (ręcznie, z formularza na stronie, z maila
 * rozpoznanego jako formularz, z wątku w Poczcie oznaczonego jako lead) i każda z nich publikuje
 * już [NewLeadCreatedEvent]. Podpięcie się pod to zdarzenie zamiast pod cztery handlery znaczy,
 * że piąta ścieżka policzy się sama — o ile publikuje zdarzenie, co jest tu regułą.
 *
 * `@EventListener`, nie `@TransactionalEventListener`: nadawcy publikują zdarzenie już PO
 * commicie własnej transakcji (część z nich w korutynie na `Dispatchers.IO`, gdzie transakcji
 * nie ma wcale). Ingest i tak nie rzuca, więc metryka nie ma jak popsuć tworzenia leada.
 */
@Component
class LeadMetricsListener(
    private val businessEventPublisher: BusinessEventPublisher
) {
    @EventListener
    fun on(event: NewLeadCreatedEvent) {
        businessEventPublisher.publish(
            tenantId = event.studioId,
            type = BusinessEventType.LEAD_CREATED,
            dimensionValue = event.leadSource.name,
            attributes = mapOf("leadId" to event.leadId.value.toString()),
            occurredAt = event.createdAt
        )
    }
}
