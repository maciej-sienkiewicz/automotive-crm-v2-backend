package pl.detailing.crm.leads.analytics

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.conversation.LeadConversationState
import pl.detailing.crm.leads.conversation.LeadConversationStateService
import pl.detailing.crm.leads.conversation.LeadTurn
import pl.detailing.crm.leads.conversation.LeadTurnResolver
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Pieniądze czekające na odpowiedź studia — stan BIEŻĄCY, nie okno raportu.
 *
 * Ten sam rachunek napędza pasmo „Czeka na Ciebie" w analityce leadów, priorytetową
 * podpowiedź na Tablicy i sekcję kolejki o tej samej nazwie. Jedno źródło prawdy —
 * ta sama liczba nie ma prawa rozjechać się między tymi ekranami.
 *
 * Liczy się to, w czym ruch jest po naszej stronie, wprost z [LeadTurnResolver] —
 * czyli z tej samej reguły, którą widzi użytkownik w kolejce. Wcześniej stał tu
 * własny, uboższy wariant tej reguły: patrzył wyłącznie na `replyState`, więc lead
 * bez wątku (telefon, formularz, dodany ręcznie) nie wchodził do rachunku w ogóle,
 * choć w kolejce stał na czerwono. Tablica i kolejka mówiły o tym samym studiu
 * dwie różne rzeczy.
 *
 * Świadomie poza zakresem dat: zaległa odpowiedź nie przestaje być zaległa dlatego,
 * że ktoś przełączył widok na „ostatnie 30 dni". Rozmowa sprzed czterdziestu dni,
 * w której klient wciąż czeka, jest tą najpilniejszą.
 */
@Service
class AwaitingWorkService(
    private val leadRepository: LeadRepository,
    private val conversationStates: LeadConversationStateService,
    private val turnResolver: LeadTurnResolver
) {

    @Transactional(readOnly = true)
    fun awaitingWork(studioId: StudioId): AwaitingWorkDto {
        val open = leadRepository.findByStudioIdAndStatusIn(studioId.value, OPEN_STATUSES)
        if (open.isEmpty()) return EMPTY

        val states = conversationStates.statesOf(studioId.value, open)
        val waiting = open.mapNotNull { lead ->
            val turn = turnResolver.resolve(lead, states[lead.id] ?: LeadConversationState.NONE)
            if (turn.turn != LeadTurn.OURS) return@mapNotNull null
            turn.since?.let { lead to it }
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
