package pl.detailing.crm.leads.update

import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.shared.LeadChangedEvent
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.StudioId
import java.time.Instant

/**
 * „Ruch jest u mnie" — ręczne obejście reguły, która wnioskuje czyj ruch z rozmowy.
 *
 * Reguła myli się zawsze w jednym przypadku i jest to przypadek codzienny: klient
 * dzwoni i prosi o przesłanie oferty mailem. Odnotowanie tej rozmowy stempluje
 * reakcję studia, więc sprawa schodzi do „U klienta" — a klient czeka na coś,
 * czego jeszcze nie wysłaliśmy. Sprawa wygląda na zdrową i znika z oczu.
 *
 * To jest odpowiednik „Oznacz jako nieprzeczytaną" z poczty: system uznał, że
 * załatwione, człowiek mówi, że nie. Każde wnioskowanie stanu musi mieć takie
 * obejście — inaczej użytkownik przestaje karmić system danymi, bo za każde
 * uczciwe odnotowanie kontaktu dostaje zniknięcie sprawy z listy.
 *
 * ── Kasowanie jest ważniejsze od ustawiania ──────────────────────────────────
 *
 * Pole, które trzeba czyścić ręcznie, zamienia się w drugą listę do pilnowania
 * i umiera w trzy tygodnie. Dlatego dług kasuje DOWÓD spłaty, nie człowiek:
 *
 *  • nasza wiadomość w wątku ([LeadFirstResponseListener]) — spłaciliśmy dokładnie to,
 *    co obiecaliśmy;
 *  • kolejny kontakt poza pocztą zamknięty odpowiedzią „czekam na klienta";
 *  • rozstrzygnięcie sprawy ([LeadStatusService]) — rezerwacja, wygrana, przegrana.
 *
 * Czego dług NIE kasuje: wiadomości PRZYCHODZĄCEJ. Klient dopominający się
 * o obiecaną ofertę nie zdejmuje z nas długu, tylko go podkreśla — a to jest
 * dokładnie ten moment, w którym mechanizm musi zadziałać najmocniej.
 */
@Service
class LeadOwedService(
    private val leadRepository: LeadRepository,
    private val eventPublisher: ApplicationEventPublisher
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Zgłoszenie długu. Jedyne wejście: odpowiedź „mam coś wysłać" w oknie kontaktu
     * poza pocztą — czyli chwila, w której obietnica pada. Osobnego przycisku „cofnij
     * do mojego ruchu" świadomie NIE MA: przycisk, który trzeba sobie wytłumaczyć,
     * jest gorszy niż jego brak, a to pytanie ma sens tylko tam, gdzie zna się
     * odpowiedź bez zastanowienia.
     */
    @Transactional
    fun declare(lead: LeadEntity, note: String?): LeadEntity {
        /*
         * Powtórne zgłoszenie NIE przesuwa daty. Wiek długu jest tym, co ustawia
         * sprawę w kolejce; odświeżanie go przy każdym kliknięciu spychałoby
         * najstarszy dług na dół dokładnie wtedy, gdy ktoś się nim zainteresował.
         */
        if (lead.owedSince == null) lead.owedSince = Instant.now()
        note?.trim()?.takeIf { it.isNotEmpty() }?.let { lead.owedNote = it.take(MAX_NOTE) }
        lead.updatedAt = Instant.now()
        val saved = leadRepository.save(lead)
        publishChanged(saved)
        log.debug("[LEADS] Dług studia zgłoszony na leadzie {}", lead.id)
        return saved
    }

    /**
     * Zdjęcie długu przez dowód spłaty. Cicho i bez zdarzenia, gdy nie było długu —
     * wołają to automaty przy każdej wysyłce i każdej zmianie statusu.
     *
     * Nie ma ręcznego odpowiednika i nie jest potrzebny: każda droga, którą obietnica
     * może zostać spełniona, kończy się tutaj sama.
     */
    @Transactional
    fun settle(lead: LeadEntity) {
        if (lead.owedSince == null) return
        lead.owedSince = null
        lead.owedNote = null
        lead.updatedAt = Instant.now()
        leadRepository.save(lead)
        publishChanged(lead)
        log.debug("[LEADS] Dług studia spłacony na leadzie {}", lead.id)
    }

    private fun publishChanged(lead: LeadEntity) {
        eventPublisher.publishEvent(
            LeadChangedEvent(
                source = this,
                studioId = StudioId(lead.studioId),
                leadId = LeadId(lead.id)
            )
        )
    }

    private companion object {
        /** Tyle, ile mieści kolumna owed_note — i tyle, ile da się przeczytać na karcie. */
        const val MAX_NOTE = 500
    }
}
