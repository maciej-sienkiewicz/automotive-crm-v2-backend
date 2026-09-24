package pl.detailing.crm.leads.conversation

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.comms.infrastructure.CommThreadRepository
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.update.LeadFirstResponseListener
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.util.UUID

/**
 * Pierwsza wiadomość napisana z leada, który nie ma wątku, zakłada mu wątek.
 *
 * Leady z webhooka formularza, z telefonu i z wielkich wątków formularzy sprzed V157
 * nie miały z czego odpisać: „Odpisz klientowi" wymagało wątku, a wątek powstawał
 * wyłącznie z poczty przychodzącej. Klient czekał, a jedyna droga prowadziła przez
 * skrzynkę i ręczne przepisywanie adresu.
 *
 * Wysyłka idzie zwykłą ścieżką (nowa wiadomość, nowy wątek); tutaj tylko przypinamy
 * powstały wątek do leada. Od tej chwili odpis klienta wraca po `In-Reply-To` do tego
 * wątku, a wątek JEST historią leada — jak przy każdym leadzie z maila.
 */
@Service
class LeadConversationBinder(
    private val leadRepository: LeadRepository,
    private val threadRepository: CommThreadRepository,
    private val firstResponse: LeadFirstResponseListener
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Sprawdzenie PRZED wysyłką: mail do klienta nieistniejącego leada ma nie wyjść wcale. */
    @Transactional(readOnly = true)
    fun requireLead(studioId: StudioId, leadId: UUID) {
        leadRepository.findByIdAndStudioId(leadId, studioId.value)
            ?: throw NotFoundException("Nie znaleziono leada")
    }

    /**
     * Po udanej wysyłce. Lead, który zdążył dostać wątek (odpis klienta, drugie okno),
     * zostaje przy swoim — nie przepinamy historii, która już istnieje.
     */
    @Transactional
    fun bind(studioId: StudioId, leadId: UUID, threadId: UUID, sentAt: Instant) {
        val lead = leadRepository.findByIdAndStudioId(leadId, studioId.value) ?: return
        val thread = threadRepository.findByIdAndStudioId(threadId, studioId.value) ?: return

        if (lead.threadId == null && thread.leadId == null && leadRepository.findByThreadId(thread.id) == null) {
            lead.threadId = thread.id
            lead.updatedAt = Instant.now()
            leadRepository.save(lead)
            thread.leadId = lead.id
            threadRepository.save(thread)
            log.info("[LEADS] Lead {} dostał wątek {} z pierwszej wysłanej wiadomości", lead.id, thread.id)
        }

        // Nasłuch „odpisaliśmy" biegnie asynchronicznie po imporcie wysłanej wiadomości
        // i mógł szukać leada po wątku, zanim go tu przypięliśmy — księgujemy więc sami.
        if (lead.threadId == thread.id) firstResponse.recordResponse(lead, sentAt)
    }
}
