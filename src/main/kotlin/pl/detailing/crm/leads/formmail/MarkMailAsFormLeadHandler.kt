package pl.detailing.crm.leads.formmail

import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.comms.domain.CommDirection
import pl.detailing.crm.comms.infrastructure.CommMessageRepository
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.util.UUID

data class MarkMailAsFormLeadCommand(
    val studioId: StudioId,
    val messageId: UUID,
    val userName: String
)

data class MarkMailAsFormLeadOutcome(
    val sourceId: UUID,
    val senderEmail: String,
    /** CREATED | REJECTED | FAILED | ALREADY_PROCESSED */
    val status: String,
    val leadId: UUID?,
    val reason: String?
)

/**
 * „Oznacz mail jako lead z formularza" — jedno kliknięcie robi dwie rzeczy.
 *
 * 1. Zapisuje NADAWCĘ maila jako źródło formularzowe: od tej chwili każdy kolejny
 *    mail z tego adresu przechodzi przez odczyt LLM-em i sam staje się leadem
 *    (patrz [FormMailAutoLeadListener]).
 * 2. Przetwarza oznaczony mail od razu, synchronicznie — użytkownik, który właśnie
 *    patrzy na zgłoszenie klienta, ma dostać leada teraz, a nie „kiedyś".
 *
 * Świadomie NIE przetwarzamy wstecz całej skrzynki. W skrzynce potrafią leżeć
 * lata powiadomień, z których większość dawno obsłużono poza CRM-em — zalanie
 * tabeli leadów setkami martwych wpisów pogrzebałoby te żywe. Wstecz sięga
 * użytkownik: oznaczając ręcznie te stare maile, na których mu zależy.
 */
@Service
class MarkMailAsFormLeadHandler(
    private val messageRepository: CommMessageRepository,
    private val sourceRepository: FormMailSourceRepository,
    private val processor: FormMailLeadProcessor,
    private val transactionTemplate: TransactionTemplate
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun handle(command: MarkMailAsFormLeadCommand): MarkMailAsFormLeadOutcome {
        val message = messageRepository.findByIdAndStudioId(command.messageId, command.studioId.value)
            ?: throw NotFoundException("Nie znaleziono wiadomości")
        if (message.direction != CommDirection.INBOUND) {
            throw ValidationException("Leadem z formularza może być tylko wiadomość przychodząca")
        }

        val senderEmail = message.fromEmail.trim().lowercase()
        val source = registerSource(command, senderEmail)

        return when (val result = processor.process(source, message)) {
            is FormMailProcessResult.Created ->
                outcome(source, "CREATED", leadId = result.leadId)
            is FormMailProcessResult.Rejected ->
                outcome(source, "REJECTED", reason = result.reason)
            is FormMailProcessResult.Failed ->
                outcome(source, "FAILED", reason = result.reason)
            is FormMailProcessResult.AlreadyProcessed ->
                outcome(source, "ALREADY_PROCESSED", leadId = result.leadId)
        }
    }

    /**
     * Nadawca do rejestru — idempotentnie. Ponowne oznaczenie maila z już znanego
     * adresu niczego nie psuje, a wyłączone źródło wraca do życia: kliknięcie
     * „oznacz jako formularz" jest jednoznaczną deklaracją, że ma działać.
     */
    private fun registerSource(command: MarkMailAsFormLeadCommand, senderEmail: String): FormMailSourceEntity {
        sourceRepository.findByStudioIdAndSenderEmail(command.studioId.value, senderEmail)?.let { existing ->
            if (!existing.active) {
                existing.active = true
                transactionTemplate.execute { sourceRepository.save(existing) }
                log.info("[FORM_MAIL] Źródło {} włączone ponownie", senderEmail)
            }
            return existing
        }

        return try {
            transactionTemplate.execute {
                sourceRepository.save(
                    FormMailSourceEntity(
                        studioId = command.studioId.value,
                        senderEmail = senderEmail,
                        createdByName = command.userName
                    )
                )
            }!!.also {
                log.info("[FORM_MAIL] Nadawca {} oznaczony jako formularz (studio {})", senderEmail, command.studioId.value)
            }
        } catch (e: DataIntegrityViolationException) {
            // Dwa kliknięcia naraz — wygrał pierwszy zapis, czytamy jego wiersz.
            sourceRepository.findByStudioIdAndSenderEmail(command.studioId.value, senderEmail)
                ?: throw e
        }
    }

    private fun outcome(
        source: FormMailSourceEntity,
        status: String,
        leadId: UUID? = null,
        reason: String? = null
    ) = MarkMailAsFormLeadOutcome(
        sourceId = source.id,
        senderEmail = source.senderEmail,
        status = status,
        leadId = leadId,
        reason = reason
    )
}
