package pl.detailing.crm.leads.conversation

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.comms.infrastructure.CommMessageRepository
import pl.detailing.crm.leads.infrastructure.LeadEntity
import java.time.Instant
import java.util.UUID

/**
 * Ustala dla leadów, czyj jest ruch w rozmowie.
 *
 * Liczone przy odczycie, nie trzymane na leadzie. Denormalizacja wymagałaby haczyków
 * w dwóch miejscach — przy pobieraniu poczty i przy wysyłce — i pierwszy przeoczony
 * haczyk zostawiłby lead z informacją nieprawdziwą, ale wyglądającą na aktualną.
 * Wiadomości są jedynym źródłem prawdy, a strona listy to jedno zapytanie grupujące.
 */
@Service
class LeadConversationStateService(
    private val messageRepository: CommMessageRepository
) {

    /** Stany rozmów dla całej strony listy — jedno zapytanie na stronę, nie na lead. */
    @Transactional(readOnly = true)
    fun statesOf(studioId: UUID, leads: Collection<LeadEntity>): Map<UUID, LeadConversationState> {
        val threadIdByLead = leads.mapNotNull { lead -> lead.threadId?.let { lead.id to it } }
        if (threadIdByLead.isEmpty()) return emptyMap()

        val byThread = messageRepository
            .findLastDirectionTimestamps(studioId, threadIdByLead.map { it.second }.distinct())
            .associate { row ->
                (row[0] as UUID) to LeadConversationState.of(
                    lastInboundAt = row[1] as Instant?,
                    lastOutboundAt = row[2] as Instant?
                )
            }

        return threadIdByLead.mapNotNull { (leadId, threadId) ->
            byThread[threadId]?.let { leadId to it }
        }.toMap()
    }

    @Transactional(readOnly = true)
    fun stateOf(studioId: UUID, lead: LeadEntity): LeadConversationState =
        statesOf(studioId, listOf(lead))[lead.id] ?: LeadConversationState.NONE
}
