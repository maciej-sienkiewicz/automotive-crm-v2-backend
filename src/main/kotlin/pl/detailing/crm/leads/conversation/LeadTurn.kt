package pl.detailing.crm.leads.conversation

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import java.time.Duration
import java.time.Instant

/**
 * Czyj jest ruch w sprawie — JEDNA reguła dla całego backendu.
 *
 * [LeadConversationState] mówi tylko o korespondencji i dla leada bez wątku
 * (telefon, formularz, dodany ręcznie) nie ma nic do powiedzenia. Tymczasem od
 * odpowiedzi na to pytanie zależy już: sekcja kolejki, kwota „czeka na Ciebie"
 * na Tablicy, plakietka w menu i rachunek „ucichłych pieniędzy" w analityce.
 * Cztery miejsca, w których ta sama sprawa nie ma prawa być raz zaległością,
 * a raz spokojnym czekaniem.
 *
 * Ta reguła jest lustrem `describeLeadUrgency` z frontendu (leadUrgency.ts) i ma
 * nim pozostać: rozjazd między nimi objawia się jako liczba w nagłówku sekcji
 * inna niż liczba wierszy pod nią.
 */
enum class LeadTurn {
    /** Klient czeka na nas. */
    OURS,

    /** My czekamy na klienta. */
    CLIENT,

    /** Sprawa rozstrzygnięta — nikt na nic nie czeka. */
    SETTLED
}

/**
 * Czyj ruch i od kiedy.
 *
 * [since] jest null wyłącznie dla [LeadTurn.SETTLED]: w każdym innym przypadku
 * oczekiwanie ma początek, bo inaczej nie dałoby się powiedzieć, jak długo trwa.
 */
data class LeadTurnState(val turn: LeadTurn, val since: Instant?) {
    fun waitingFor(now: Instant): Duration = since?.let { Duration.between(it, now) } ?: Duration.ZERO
}

/**
 * Progi stygnięcia studia: po ilu godzinach nasza zwłoka staje się długiem,
 * a cisza klienta — momentem na przypomnienie.
 *
 * Wartości domyślne są tu, a nie w kontrolerze, bo czyta je teraz i analityka,
 * i rachunek zaległości, i ustawienia firmy. Migracja V145 wstawia te same liczby
 * do bazy; te stałe obsługują studio, które nie ma jeszcze wiersza ustawień.
 */
data class LeadStagnationThresholds(
    val ourReplyHours: Int,
    val clientSilenceHours: Int
) {
    companion object {
        /** 24 h na naszą odpowiedź, 5 dni ciszy klienta — to samo, co pokazuje interfejs. */
        val DEFAULT = LeadStagnationThresholds(ourReplyHours = 24, clientSilenceHours = 120)
    }
}

/**
 * Rozstrzyga, czyj jest ruch, i czyta progi studia.
 *
 * Kolejność gałęzi w [resolve] jest kolejnością siły dowodu, nie wygody:
 *
 *  1. Sprawa zamknięta — żadne oczekiwanie w niej nie trwa, choćby klient napisał
 *     wczoraj „dziękuję".
 *  2. DŁUG ZADEKLAROWANY RĘCZNIE — człowiek powiedział wprost „mam coś wysłać".
 *     To jest jedyne miejsce, w którym wiedza użytkownika bije wnioskowanie
 *     z korespondencji, i dlatego stoi nad nim, a nie pod nim.
 *  3. Korespondencja, o ile backend ma ją czym policzyć.
 *  4. Leady bez rozmowy: dopóki nikt z naszej strony się nie odezwał, ruch jest nasz.
 */
@Service
class LeadTurnResolver(
    private val settingsRepository: StudioSettingsRepository
) {

    @Transactional(readOnly = true)
    fun thresholdsOf(studioId: StudioId): LeadStagnationThresholds =
        settingsRepository.findById(studioId.value).orElse(null)
            ?.let {
                LeadStagnationThresholds(
                    ourReplyHours = it.leadStagnantOurThresholdHours,
                    clientSilenceHours = it.leadStagnantClientThresholdHours
                )
            }
            ?: LeadStagnationThresholds.DEFAULT

    fun resolve(lead: LeadEntity, conversation: LeadConversationState): LeadTurnState {
        if (lead.status in CLOSED_STATUSES) return SETTLED

        /*
         * Dług spłaca DOWÓD, nie upływ czasu: nasza wiadomość wysłana po tym, jak
         * dług powstał. Kasowaniem zajmuje się LeadOwedService (przy wysyłce, przy
         * kolejnym kontakcie, przy rozstrzygnięciu sprawy), ale to jest automat
         * asynchroniczny — ten warunek jest zaworem na te kilkaset milisekund,
         * w których pole jeszcze stoi, a wiadomość już poszła.
         */
        lead.owedSince?.let { owedSince ->
            val settled = conversation.lastOutboundAt?.isAfter(owedSince) ?: false
            if (!settled) return LeadTurnState(LeadTurn.OURS, owedSince)
        }

        if (conversation.replyState == LeadReplyState.AWAITING_OUR_REPLY) {
            val since = conversation.waitingSince
            if (since != null) {
                /*
                 * Kontakt poza pocztą JEST odpowiedzią, tyle że replyState jej nie widzi:
                 * telefon ląduje w lead_callbacks, a stan rozmowy liczy się z comm_messages.
                 * Jedynym śladem takiego kontaktu na samym leadzie jest firstResponseAt.
                 *
                 * ⚠️ To łapie PIERWSZY kontakt, nie każdy — firstResponseAt z definicji
                 * nie przesuwa się przy kolejnych telefonach (zmiana O4 w
                 * docs/leads-queue-backend-spec.md). Do czasu tamtej kolumny ręczny dług
                 * i jego zdjęcie są obejściem, którym użytkownik może to naprawić.
                 */
                val respondedLater = lead.firstResponseAt?.isAfter(since) ?: false
                return if (respondedLater) LeadTurnState(LeadTurn.CLIENT, lead.firstResponseAt)
                else LeadTurnState(LeadTurn.OURS, since)
            }
        }

        if (conversation.replyState == LeadReplyState.AWAITING_CLIENT_REPLY) {
            conversation.waitingSince?.let { return LeadTurnState(LeadTurn.CLIENT, it) }
        }

        // Lead bez wątku albo z wątkiem jeszcze pustym. Brak rozmowy nie znaczy
        // „nie ma na co czekać" — znaczy „czekanie trzeba policzyć z innych pól".
        return if (lead.firstResponseAt == null) LeadTurnState(LeadTurn.OURS, lead.createdAt)
        else LeadTurnState(LeadTurn.CLIENT, lead.firstResponseAt)
    }

    /**
     * Rozmowa, która ucichła: ruch jest u klienta i trwa dłużej niż próg studia.
     *
     * Świadomie NIE „lead starszy niż X dni od wpłynięcia". Zapytanie sprzed
     * miesiąca, w którym klient odpisał wczoraj, jest żywe; zapytanie sprzed
     * tygodnia, w którym cisza trwa od siedmiu dni, jest ucichłe. Liczy się wiek
     * CISZY, nie wiek sprawy — i to jest ta sama definicja, którą widzi użytkownik
     * w kolejce, więc kwota w analityce zgadza się z liczbą wierszy w sekcji.
     */
    fun isSilent(state: LeadTurnState, thresholds: LeadStagnationThresholds, now: Instant): Boolean =
        state.turn == LeadTurn.CLIENT &&
            state.waitingFor(now) >= Duration.ofHours(thresholds.clientSilenceHours.toLong())

    private companion object {
        val CLOSED_STATUSES = setOf(LeadStatus.COMPLETED, LeadStatus.LOST, LeadStatus.NO_SHOW)
        val SETTLED = LeadTurnState(LeadTurn.SETTLED, null)
    }
}
