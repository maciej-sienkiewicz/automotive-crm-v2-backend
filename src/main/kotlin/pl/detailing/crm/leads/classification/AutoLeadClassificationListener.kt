package pl.detailing.crm.leads.classification

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import pl.detailing.crm.comms.domain.CommInboundMessageStoredEvent
import pl.detailing.crm.comms.infrastructure.CommMessageRepository
import pl.detailing.crm.comms.infrastructure.CommThreadRepository
import pl.detailing.crm.leads.formmail.FormMailSourceEntity
import pl.detailing.crm.leads.formmail.FormMailSourceRepository
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.studio.settings.StudioSettingsRepository

/**
 * Automat: przychodzące zapytanie klienta samo staje się leadem.
 *
 * Po zatwierdzeniu transakcji syncu i asynchronicznie — klasyfikacja idzie do modelu
 * i trwa sekundy, a pętla IMAP ma dalej zbierać pocztę, nie czekać na odpowiedź.
 *
 * KASKADA ODSIEWU, od najtańszego progu do najdroższego. Kolejność nie jest kwestią
 * gustu: przez ten kod przechodzi KAŻDA przychodząca wiadomość każdego studia, a tylko
 * ułamek z nich jest zapytaniem. Każdy próg, który da się rozstrzygnąć bez zapytania
 * bazy, stoi przed tym, który jej wymaga; każdy, który da się rozstrzygnąć bez modelu,
 * stoi przed modelem.
 *
 *  1. GLOBALNY WYŁĄCZNIK — jedna właściwość gasi funkcję na całej platformie.
 *     Pole w pamięci, zero zapytań.
 *  2. FLAGA STUDIA — bez niej system zachowuje się dokładnie jak przed wdrożeniem.
 *  3. PRÓG CZASOWY — poczta starsza niż moment włączenia flagi. Doczytanie starego
 *     folderu (backfill, reset UIDVALIDITY) potrafi wsypać lata korespondencji naraz;
 *     bez tego progu zapłacilibyśmy za przeczytanie każdej z nich i pogrzebali żywe
 *     leady pod setkami dawno obsłużonych. Ta sama ochrona co przy automacie formularzy.
 *  4. NAGŁÓWKI AUTOMATU — newsletter i autoresponder nigdy nie są zapytaniem o wycenę,
 *     a przedstawiają się same ([pl.detailing.crm.comms.domain.AutomatedMailDetector]).
 *     Najtańszy odsiew, jaki mamy: werdykt przyjeżdża w zdarzeniu.
 *  5. WĄTEK MA JUŻ LEADA — rozmowa została już rozpoznana, ręcznie albo przez nas.
 *  6. KOLEJNA WIADOMOŚĆ W WĄTKU — klasyfikujemy PIERWSZĄ wiadomość rozmowy. Klient,
 *     który dopisuje „a, jeszcze jedno", nie zakłada nowego zapytania, a każda kolejna
 *     wiadomość kosztowałaby tyle samo co pierwsza. Wyjątek niżej.
 *  7. DZIENNIK — tę wiadomość już przetwarzaliśmy (w [AutoLeadProcessor]).
 *  8. LIMIT DZIENNY — również w procesorze, tuż przed pierwszym tokenem.
 *
 * WYJĄTEK DLA ROBOTÓW FORMULARZA: powiadomienia z formularza mają jednego nadawcę
 * i jeden temat, więc IMAP skleja je w JEDEN wątek — a każde z nich jest osobnym
 * zgłoszeniem osobnego człowieka. Reguła „tylko pierwsza w wątku" zrobiłaby z całego
 * formularza jednego leada rocznie. Dla adresów z rejestru `form_mail_sources`
 * klasyfikujemy więc każdą wiadomość osobno, tak jak robi to dziś
 * [pl.detailing.crm.leads.formmail.FormMailAutoLeadListener] — który przy włączonej
 * fladze milczy, żeby o jednym mailu nie decydowały dwa automaty naraz.
 */
@Component
class AutoLeadClassificationListener(
    private val studioSettingsRepository: StudioSettingsRepository,
    private val sourceRepository: FormMailSourceRepository,
    private val messageRepository: CommMessageRepository,
    private val threadRepository: CommThreadRepository,
    private val leadRepository: LeadRepository,
    private val classificationRepository: LeadMessageClassificationRepository,
    private val processor: AutoLeadProcessor,
    @Value("\${crm.ai.lead-classification.enabled:true}") private val globallyEnabled: Boolean
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onInboundMessageStored(event: CommInboundMessageStoredEvent) {
        if (!globallyEnabled) return

        val settings = studioSettingsRepository.findById(event.studioId).orElse(null) ?: return
        if (!settings.autoLeadClassificationEnabled) return

        // Brak stempla przy włączonej fladze traktujemy jak „włączona od zawsze": kolumna
        // dopiero powstała i studio, które zdążyło ją zapalić przed tą migracją, nie ma
        // powodu tracić poczty. Nowe włączenia stempel dostają (patrz CompanyController).
        val enabledAt = settings.autoLeadClassificationEnabledAt
        if (enabledAt != null && event.sentAt.isBefore(enabledAt)) {
            log.debug(
                "[LEAD_CLASSIFY] Wiadomość {} starsza niż włączenie funkcji — pomijam (backfill)",
                event.messageId
            )
            return
        }

        if (event.automated) {
            log.debug("[LEAD_CLASSIFY] Wiadomość {} oznaczona nagłówkami jako automat — pomijam", event.messageId)
            return
        }

        val formSource = formSourceFor(event)
        val thread = threadRepository.findById(event.threadId).orElse(null) ?: return

        if (formSource == null) {
            // Zwykły nadawca: jeden wątek to jedna sprawa, a lead pod nią jest jeden.
            if (thread.leadId != null || leadRepository.findByThreadId(thread.id) != null) return
            if (thread.inboundCount > 1) {
                log.debug("[LEAD_CLASSIFY] Wiadomość {} nie jest pierwszą w wątku — pomijam", event.messageId)
                return
            }
        }

        // Tani strzał po indeksie unikalnym, zanim wczytamy treść wiadomości: powtórka
        // po restarcie albo po ponownym syncu ma odpaść bez czytania czegokolwiek więcej.
        if (classificationRepository.existsByMessageId(event.messageId)) return

        val message = messageRepository.findById(event.messageId).orElse(null) ?: return

        // Procesor sam pilnuje idempotencji (dziennik po message_id), limitu i transakcji.
        runCatching { processor.process(message, thread, formSource) }
            .onFailure { log.warn("[LEAD_CLASSIFY] Automat dla wiadomości {} zawiódł: {}", event.messageId, it.message) }
    }

    /** Aktywny robot formularza dla tego nadawcy albo null (ścieżka 99% poczty). */
    private fun formSourceFor(event: CommInboundMessageStoredEvent): FormMailSourceEntity? =
        sourceRepository
            .findByStudioIdAndSenderEmail(event.studioId, event.fromEmail.trim().lowercase())
            ?.takeIf { it.active }
}
