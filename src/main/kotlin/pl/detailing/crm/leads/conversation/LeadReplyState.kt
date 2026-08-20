package pl.detailing.crm.leads.conversation

import java.time.Duration
import java.time.Instant

/**
 * Czyj jest ruch w rozmowie z klientem.
 *
 * To NIE jest status leada i nie ma nim być. Status opisuje etap sprzedaży i jest
 * decyzją człowieka („czy to już przegrane?"), a czyj ruch — faktem, który wynika
 * wprost z kierunku ostatniej wiadomości. Wciśnięcie tego faktu w status kończyłoby
 * się dwoma szkodami naraz: lejek zaśmiecony stanami, które nie są etapami, oraz
 * pole utrzymywane ręcznie mimo że system zna odpowiedź — a więc pole, które
 * pierwszego dnia jest prawdziwe i tydzień później już nie.
 */
enum class LeadReplyState {
    /** Klient napisał ostatni — piłka po naszej stronie. */
    AWAITING_OUR_REPLY,

    /** My napisaliśmy ostatni — czekamy na decyzję klienta. */
    AWAITING_CLIENT_REPLY,

    /** Lead bez korespondencji (telefon, formularz) albo wątek jeszcze pusty. */
    NO_CONVERSATION
}

/**
 * Stan rozmowy jednego leada: czyj ruch i od kiedy.
 *
 * [waitingSince] to moment, od którego trwa oczekiwanie — czyli czas ostatniej
 * wiadomości. Wieku nie liczymy tutaj na sztywno w dniach, bo interfejs pokazuje go
 * względnie („2 dni temu"), a przeliczanie w dwóch miejscach rozjeżdża się na granicy doby.
 */
data class LeadConversationState(
    val replyState: LeadReplyState,
    val lastInboundAt: Instant?,
    val lastOutboundAt: Instant?
) {
    val waitingSince: Instant?
        get() = when (replyState) {
            LeadReplyState.AWAITING_OUR_REPLY -> lastInboundAt
            LeadReplyState.AWAITING_CLIENT_REPLY -> lastOutboundAt
            LeadReplyState.NO_CONVERSATION -> null
        }

    /** Ile trwa bieżące oczekiwanie; null, gdy nie ma na co czekać. */
    fun waitingFor(now: Instant): Duration? = waitingSince?.let { Duration.between(it, now) }

    companion object {
        val NONE = LeadConversationState(LeadReplyState.NO_CONVERSATION, null, null)

        /**
         * Remis (obie wiadomości z tą samą sekundą) rozstrzygamy na naszą niekorzyść:
         * lepiej raz za dużo przypomnieć o odpowiedzi niż raz o niej zapomnieć.
         */
        fun of(lastInboundAt: Instant?, lastOutboundAt: Instant?): LeadConversationState {
            val state = when {
                lastInboundAt == null && lastOutboundAt == null -> LeadReplyState.NO_CONVERSATION
                lastOutboundAt == null -> LeadReplyState.AWAITING_OUR_REPLY
                lastInboundAt == null -> LeadReplyState.AWAITING_CLIENT_REPLY
                lastInboundAt >= lastOutboundAt -> LeadReplyState.AWAITING_OUR_REPLY
                else -> LeadReplyState.AWAITING_CLIENT_REPLY
            }
            return LeadConversationState(state, lastInboundAt, lastOutboundAt)
        }
    }
}
