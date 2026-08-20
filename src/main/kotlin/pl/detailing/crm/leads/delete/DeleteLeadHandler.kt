package pl.detailing.crm.leads.delete

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.comms.infrastructure.CommThreadRepository
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.infrastructure.LeadServiceItemRepository
import pl.detailing.crm.leads.infrastructure.LeadStatusHistoryRepository
import pl.detailing.crm.leads.infrastructure.LeadTagRepository
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import java.util.UUID

/**
 * Usunięcie leada — pomyłka, duplikat, test.
 *
 * Kasujemy naprawdę, a nie oznaczamy jako usunięty. Lead nie jest dokumentem
 * księgowym: nie ma obowiązku przechowywania, a „usunięty, ale nadal liczony
 * w statystykach" byłby gorszy niż brak przycisku. Historia statusów i tagi znikają
 * razem z nim, bo bez leada nie znaczą nic.
 *
 * Czego NIE ruszamy: samej korespondencji. Wątek zostaje w skrzynce, traci tylko
 * powiązanie — i można go oznaczyć jako lead ponownie, co jest zwykle powodem, dla
 * którego ktoś kasuje pierwszy.
 *
 * Lead spięty z rezerwacją lub wizytą nie daje się usunąć: to już nie zapytanie,
 * tylko klient w kalendarzu, a usunięcie zostawiłoby wiszące powiązanie.
 */
@Service
class DeleteLeadHandler(
    private val leadRepository: LeadRepository,
    private val itemRepository: LeadServiceItemRepository,
    private val tagRepository: LeadTagRepository,
    private val historyRepository: LeadStatusHistoryRepository,
    private val threadRepository: CommThreadRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun handle(studioId: StudioId, leadId: UUID) {
        val lead = leadRepository.findByIdAndStudioId(leadId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono leada")

        if (lead.appointmentId != null || lead.visitId != null) {
            throw ConflictException(
                "Ten lead ma już rezerwację lub wizytę — usuń najpierw powiązanie w kalendarzu"
            )
        }

        lead.threadId?.let { threadId ->
            threadRepository.findByIdAndStudioId(threadId, studioId.value)?.let { thread ->
                thread.leadId = null
                threadRepository.save(thread)
            }
        }

        historyRepository.deleteAll(historyRepository.findByLeadIdOrderByCreatedAtAsc(leadId))
        itemRepository.deleteByLeadId(leadId)
        tagRepository.deleteByLeadId(leadId)
        leadRepository.delete(lead)

        log.info("[LEADS] Lead {} deleted from studio {}", leadId, studioId.value)
    }
}
