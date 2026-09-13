package pl.detailing.crm.leads.analytics

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.conversation.LeadConversationStateService
import pl.detailing.crm.leads.conversation.LeadReplyState
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Pieniądze czekające na odpowiedź studia — stan BIEŻĄCY, nie okno raportu.
 *
 * Wyciągnięte z [GetLeadAnalyticsHandler] do osobnej usługi, bo ten sam rachunek
 * napędza teraz dwa miejsca: pasmo „Czeka na Ciebie" w analityce leadów oraz
 * priorytetową (czerwoną) podpowiedź na Tablicy. Jedno źródło prawdy — ta sama
 * liczba nie ma prawa rozjechać się między ekranem analityki a paskiem Tablicy.
 *
 * Liczy się tylko to, w czym ostatnie słowo należy do klienta — piłka po naszej
 * stronie. Lead, w którym to my napisaliśmy ostatni, nie jest zaległością, tylko
 * czekaniem na decyzję; mieszanie tych dwóch rzeczy zamieniłoby dług w listę
 * wszystkiego.
 *
 * Świadomie poza zakresem dat: zaległa odpowiedź nie przestaje być zaległa dlatego,
 * że ktoś przełączył widok na „ostatnie 30 dni". Rozmowa sprzed czterdziestu dni,
 * w której klient wciąż czeka, jest tą najpilniejszą.
 */
@Service
class AwaitingWorkService(
    private val leadRepository: LeadRepository,
    private val conversationStates: LeadConversationStateService
) {

    @Transactional(readOnly = true)
    fun awaitingWork(studioId: StudioId): AwaitingWorkDto {
        val open = leadRepository.findByStudioIdAndStatusIn(studioId.value, OPEN_STATUSES)
        if (open.isEmpty()) return EMPTY

        val states = conversationStates.statesOf(studioId.value, open)
        val waiting = open.mapNotNull { lead ->
            val state = states[lead.id] ?: return@mapNotNull null
            if (state.replyState != LeadReplyState.AWAITING_OUR_REPLY) return@mapNotNull null
            val since = state.waitingSince ?: return@mapNotNull null
            lead to since
        }
        if (waiting.isEmpty()) return EMPTY

        val now = Instant.now()
        val oldest = waiting.minByOrNull { it.second }
        return AwaitingWorkDto(
            value = waiting.sumOf { it.first.estimatedValue },
            count = waiting.size,
            oldest = oldest?.let { (lead, since) ->
                AwaitingLeadDto(
                    leadId = lead.id.toString(),
                    // Nazwisko, jeśli je znamy; adres albo numer, jeśli nie. Byle nie „Lead #4".
                    name = lead.customerName?.takeIf { it.isNotBlank() } ?: lead.contactIdentifier,
                    vehicle = listOfNotNull(lead.vehicleBrand, lead.vehicleModel)
                        .joinToString(" ")
                        .takeIf { it.isNotBlank() },
                    value = lead.estimatedValue,
                    waitingDays = ChronoUnit.DAYS.between(since, now).coerceAtLeast(0).toInt()
                )
            }
        )
    }

    private companion object {
        val OPEN_STATUSES = setOf(LeadStatus.NEW, LeadStatus.IN_PROGRESS, LeadStatus.CONFIRMED)
        val EMPTY = AwaitingWorkDto(value = 0, count = 0, oldest = null)
    }
}
