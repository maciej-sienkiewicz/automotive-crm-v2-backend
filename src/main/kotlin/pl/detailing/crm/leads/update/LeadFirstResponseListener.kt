package pl.detailing.crm.leads.update

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import pl.detailing.crm.comms.domain.CommOutboundSentEvent
import pl.detailing.crm.leads.infrastructure.LeadEntity
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
 *
 * PO ZATWIERDZENIU TRANSAKCJI I ASYNCHRONICZNIE — dokładnie tak, jak pozostałe
 * automaty leadów karmione pocztą ([pl.detailing.crm.leads.formmail.FormMailAutoLeadListener],
 * [pl.detailing.crm.leads.classification.AutoLeadClassificationListener]).
 *
 * To nie jest kosmetyka, tylko warunek, żeby wiadomość w ogóle trafiła do CRM-a.
 * Odpowiedź wysłana z Outlooka czy telefonu wpada do nas importem z folderu
 * Wysłane, a import każdej wiadomości to jedna transakcja ([CommsIngestService]).
 * Nasłuch wpięty w TĘ SAMĄ transakcję mógł ją zatruć: `@Transactional` bez
 * własnej propagacji dołącza się do transakcji wywołującego, więc wyjątek w
 * księgowaniu leada oznaczał ją jako rollback-only. Wyjątek dawał się złapać,
 * ale decyzji o wycofaniu odwrócić już nie — zapis wiadomości przepadał przy
 * zatwierdzaniu, a synchronizacja i tak przesuwała znacznik UID, więc wiadomość
 * nie wracała już nigdy i w CRM-ie nie było jej ani w skrzynce, ani na leadzie.
 *
 * `fallbackExecution = true`, bo zdarzenie publikuje też wysyłka z CRM-a, która
 * transakcji nie otwiera.
 *
 * REQUIRES_NEW nie jest ozdobnikiem, tylko jedyną propagacją, na jaką Spring tu
 * pozwala: od 6.1 `RestrictedTransactionalEventListenerFactory` odrzuca gołe
 * `@Transactional` na nasłuchu transakcyjnym i aplikacja nie wstaje. Rozumowanie
 * stoi za tym to samo, co wyżej — nasłuch ma otwierać własną transakcję, a nie
 * dopisywać się do cudzej (przy AFTER_COMMIT: do właśnie zamkniętej).
 */
@Component
class LeadFirstResponseListener(
    private val leadRepository: LeadRepository,
    private val statusService: LeadStatusService,
    private val owedService: LeadOwedService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onOutboundSent(event: CommOutboundSentEvent) {
        // Wątek formularzowego robota zbiera zgłoszenia wielu osób i potrafi mieć
        // kilku leadów; pierwsza reakcja należy do najstarszego z nich. Zapytanie
        // o JEDEN wynik wywracałoby się tutaj na policzalności, a nie na sensie.
        val lead = leadRepository.findByThreadIdOrderByCreatedAtAsc(event.threadId).firstOrNull() ?: return
        recordResponse(lead, event.sentAt)
        log.debug("[LEADS] Odpowiedź w wątku {} odnotowana na leadzie {}", event.threadId, lead.id)
    }

    /**
     * Księgowanie odpowiedzi na leadzie — wspólne dla nasłuchu i dla pierwszej wiadomości
     * wysłanej z leada bez wątku ([pl.detailing.crm.leads.conversation.LeadConversationBinder]):
     * tam wątek powstaje w trakcie wysyłki i nasłuch może go jeszcze nie znać.
     * Idempotentne — drugi przebieg niczego nie zmienia.
     */
    fun recordResponse(lead: LeadEntity, sentAt: Instant) {
        if (lead.firstResponseAt == null) {
            lead.firstResponseAt = sentAt
            lead.updatedAt = Instant.now()
            leadRepository.save(lead)
        }

        if (lead.status == LeadStatus.NEW) {
            statusService.transition(lead, LeadStatus.IN_PROGRESS)
        }

        /*
         * Wysłana wiadomość jest DOWODEM spłaty ręcznie zgłoszonego długu („klient
         * prosił o ofertę mailem"). Kasujemy go tutaj, a nie przy odczycie, bo to
         * jest dokładnie ten moment, w którym obietnica przestaje być niespełniona —
         * i jedyny, po którym sprawa ma prawo zejść z sekcji „Czeka na Ciebie".
         */
        owedService.settle(lead)
    }
}
