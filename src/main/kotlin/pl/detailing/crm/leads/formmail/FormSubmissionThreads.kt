package pl.detailing.crm.leads.formmail

import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import pl.detailing.crm.comms.domain.CommThreadChangedEvent
import pl.detailing.crm.comms.domain.CommThreadKind
import pl.detailing.crm.comms.domain.CommThreadScreening
import pl.detailing.crm.comms.domain.MailAddressBook
import pl.detailing.crm.comms.domain.MailAddressDirectory
import pl.detailing.crm.comms.infrastructure.CommMessageEntity
import pl.detailing.crm.comms.infrastructure.CommThreadEntity
import pl.detailing.crm.comms.infrastructure.CommThreadRepository
import pl.detailing.crm.user.infrastructure.UserRepository
import java.util.UUID

/**
 * Wątek zgłoszenia z formularza ([CommThreadKind.FORM]) po odczycie treści.
 *
 * Import ustala wątek z samych nagłówków; dopiero odczyt treści mówi, KIM jest klient
 * (gdy robot nie ustawia `Reply-To`), czego dotyczy sprawa (tytuł na listę) i czy to
 * w ogóle klient (spam, test ze studia). Te trzy rzeczy dopisujemy do wątku tutaj —
 * jedno miejsce dla automatu formularzy i automatu klasyfikującego.
 *
 * Wołane wewnątrz transakcji wołającego: zmiany wątku mają się zatwierdzić razem
 * z leadem albo razem z wpisem „odrzucone" w dzienniku.
 */
@Component
class FormSubmissionThreads(
    private val threadRepository: CommThreadRepository,
    private val addressDirectory: MailAddressDirectory,
    private val userRepository: UserRepository,
    private val eventPublisher: ApplicationEventPublisher
) {

    /** Wątek tego zgłoszenia, o ile zgłoszenie ma własny wątek (a nie wielki wątek sprzed V157). */
    fun formThreadOf(message: CommMessageEntity): CommThreadEntity? =
        threadRepository.findById(message.threadId).orElse(null)
            ?.takeIf { it.kind == CommThreadKind.FORM }

    /**
     * Adres klienta ustalony bez modelu: `Reply-To` zgłoszenia, o ile wskazuje kogoś
     * spoza studia. Ma pierwszeństwo przed odczytem — to ten sam adres, na który
     * odpowiedziałby każdy program pocztowy.
     */
    fun deterministicClientEmail(message: CommMessageEntity): String? {
        val replyTo = message.replyToEmail?.let(MailAddressBook::normalize) ?: return null
        val book = addressDirectory.addressBook(message.studioId)
        return replyTo.takeIf { it != MailAddressBook.normalize(message.fromEmail) && !book.isNotAClient(it) }
    }

    /**
     * Zgłoszenie wysłał ktoś ze studia — pracownik testujący formularz. Porównujemy
     * z adresami kont użytkowników i skrzynek studia; nie kosztuje to ani tokena.
     */
    fun isStudioAddress(studioId: UUID, email: String?): Boolean {
        val normalized = email?.let(MailAddressBook::normalize) ?: return false
        if (addressDirectory.addressBook(studioId).isOwn(normalized)) return true
        return userRepository.findByStudioId(studioId).any { MailAddressBook.normalize(it.email) == normalized }
    }

    /**
     * Dopisuje do wątku to, czego nauczył odczyt: klienta (gdy import go nie znał),
     * nazwę i tytuł sprawy. Niczego, co już ustalono, nie nadpisuje.
     */
    fun enrich(
        thread: CommThreadEntity,
        clientEmail: String?,
        customerName: String?,
        title: String?
    ) {
        val book = addressDirectory.addressBook(thread.studioId)
        // Robot bez Reply-To: do tej pory drugą stroną był robot. Od teraz — klient z treści,
        // więc odpowiedź pójdzie do niego, a nie z powrotem do formularza.
        if (clientEmail != null && book.isNotAClient(thread.participantEmail)) {
            thread.participantEmail = MailAddressBook.normalize(clientEmail)
        }
        if (thread.participantName.isNullOrBlank() && !customerName.isNullOrBlank()) {
            thread.participantName = customerName.take(255)
        }
        if (thread.title.isNullOrBlank() && !title.isNullOrBlank()) {
            thread.title = title.take(300)
        }
        save(thread)
    }

    fun attachLead(thread: CommThreadEntity, leadId: UUID) {
        thread.leadId = leadId
        // Lead to decyzja „to jest klient" — werdykt automatu o spamie przestaje obowiązywać.
        thread.screening = null
        thread.screeningReason = null
        save(thread)
    }

    fun screen(thread: CommThreadEntity, screening: CommThreadScreening, reason: String) {
        // Wątek, w którym już jest lead albo już odpisaliśmy, jest rozmową — automat
        // nie ma prawa przenieść go do „Odrzuconych".
        if (thread.leadId != null || thread.outboundCount > 0) return
        thread.screening = screening
        thread.screeningReason = reason.take(300)
        save(thread)
    }

    private fun save(thread: CommThreadEntity) {
        threadRepository.save(thread)
        // Lista rozmów ma zobaczyć nowego klienta, tytuł albo przeniesienie do
        // „Odrzuconych" od razu — bez powiadomienia, bo nic nowego nie przyszło.
        eventPublisher.publishEvent(
            CommThreadChangedEvent(studioId = thread.studioId, threadId = thread.id, newMessage = false)
        )
    }
}
