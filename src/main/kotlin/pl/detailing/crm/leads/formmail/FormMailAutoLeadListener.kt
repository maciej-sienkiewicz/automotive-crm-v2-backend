package pl.detailing.crm.leads.formmail

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.beans.factory.annotation.Value
import pl.detailing.crm.comms.domain.CommInboundMessageStoredEvent
import pl.detailing.crm.comms.infrastructure.CommMessageRepository
import pl.detailing.crm.studio.settings.StudioSettingsRepository

/**
 * Automat: każdy nowy mail z oznaczonego adresu formularza staje się leadem.
 *
 * Po zatwierdzeniu transakcji syncu i asynchronicznie — odczyt LLM-em trwa
 * sekundy, a pętla IMAP ma dalej zbierać pocztę, nie czekać na model.
 *
 * WYŁĄCZONY, GDY STUDIO WŁĄCZY AUTOMATYCZNE TWORZENIE LEADÓW. Tamten automat
 * (pl.detailing.crm.leads.classification.AutoLeadClassificationListener) obejmuje
 * także maile z oznaczonych adresów — najpierw pyta model, czy zgłoszenie jest
 * zapytaniem klienta, a dopiero potem woła stąd [FormMailLeadProcessor], żeby
 * odczytał kontakt z treści. Gdyby oba nasłuchy pracowały naraz, o tym samym mailu
 * decydowałyby dwa mechanizmy, a który z nich wygra, rozstrzygałby wyścig o wpis
 * w dzienniku. Jeden mail = jeden decydent.
 *
 * Dwa progi odsiewu, oba tanie:
 *  1. Nadawca spoza rejestru → jedno zapytanie po indeksie unikalnym i koniec.
 *     To jest ścieżka 99% poczty i dlatego zdarzenie niesie adres w sobie.
 *  2. Mail STARSZY niż oznaczenie źródła → pomijamy. Doczytanie starego folderu
 *     (backfill, reset UIDVALIDITY) potrafi wsypać lata powiadomień naraz —
 *     zalanie tabeli leadów setkami dawno obsłużonych zgłoszeń pogrzebałoby
 *     te żywe. Wstecz sięga człowiek, oznaczając ręcznie konkretne maile.
 */
@Component
class FormMailAutoLeadListener(
    private val sourceRepository: FormMailSourceRepository,
    private val messageRepository: CommMessageRepository,
    private val processor: FormMailLeadProcessor,
    private val studioSettingsRepository: StudioSettingsRepository,
    @Value("\${crm.ai.lead-classification.enabled:true}") private val classificationAvailable: Boolean
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onInboundMessageStored(event: CommInboundMessageStoredEvent) {
        if (supersededByClassifier(event)) return

        val senderEmail = event.fromEmail.trim().lowercase()
        val source = sourceRepository.findByStudioIdAndSenderEmail(event.studioId, senderEmail)
            ?: return
        if (!source.active) return
        if (event.sentAt.isBefore(source.createdAt)) {
            log.debug(
                "[FORM_MAIL] Mail {} od {} starszy niż oznaczenie źródła — pomijam (backfill)",
                event.messageId, senderEmail
            )
            return
        }

        val message = messageRepository.findById(event.messageId).orElse(null) ?: return
        // Procesor sam pilnuje idempotencji (dziennik po message_id) i transakcji.
        runCatching { processor.process(source, message) }
            .onFailure { log.warn("[FORM_MAIL] Automat dla maila {} zawiódł: {}", event.messageId, it.message) }
    }

    /**
     * Czy tego maila przejmuje klasyfikator leadów. Sprawdzamy PRZED rejestrem nadawców,
     * bo to jedno zapytanie po kluczu głównym zamiast dwóch — a przy włączonej fladze
     * i tak nie mamy tu nic do roboty.
     */
    private fun supersededByClassifier(event: CommInboundMessageStoredEvent): Boolean {
        if (!classificationAvailable) return false
        return studioSettingsRepository.findById(event.studioId)
            .map { it.autoLeadClassificationEnabled }
            .orElse(false)
    }
}
