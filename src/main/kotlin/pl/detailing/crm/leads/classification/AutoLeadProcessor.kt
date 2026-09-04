package pl.detailing.crm.leads.classification

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.comms.infrastructure.CommMessageEntity
import pl.detailing.crm.comms.infrastructure.CommThreadEntity
import pl.detailing.crm.comms.infrastructure.CommThreadRepository
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.leads.create.SoleUserResolver
import pl.detailing.crm.leads.domain.LeadVehicleDetectionStatus
import pl.detailing.crm.leads.formmail.FormMailLeadProcessor
import pl.detailing.crm.leads.formmail.FormMailProcessResult
import pl.detailing.crm.leads.formmail.FormMailSourceEntity
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.update.LeadStatusService
import pl.detailing.crm.leads.vehicle.LeadThreadAttachedEvent
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.NewLeadCreatedEvent
import pl.detailing.crm.shared.StudioId
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

sealed interface AutoLeadResult {
    data class Created(val leadId: UUID) : AutoLeadResult

    /** Model przeczytał i orzekł, że to nie jest zapytanie klienta. */
    data class Rejected(val reason: String) : AutoLeadResult

    /** Do modelu w ogóle nie doszło — odsiew po tańszych kryteriach albo limit. */
    data class Skipped(val reason: String) : AutoLeadResult

    /** Model miał odpowiedzieć i nie odpowiedział. */
    data class Failed(val reason: String) : AutoLeadResult

    /** Dziennik zna już tę wiadomość — wynik pochodzi z pierwszego przebiegu. */
    data class AlreadyProcessed(val leadId: UUID?) : AutoLeadResult
}

/**
 * Zamienia jedną przychodzącą wiadomość w leada — albo w uzasadnioną odmowę.
 *
 * Kolejność jest ta sama co w [FormMailLeadProcessor] i z tego samego powodu: rozmowa
 * z modelem trwa sekundy i stoi PRZED transakcją, nie w niej. Trzymanie otwartej
 * transakcji przez czas odpowiedzi LLM-a blokowałoby pulę połączeń dokładnie wtedy,
 * gdy sync poczty ma najwięcej do roboty.
 *
 * DWIE ŚCIEŻKI TWORZENIA, bo „kto jest klientem" zależy od nadawcy:
 *
 *  • ZWYKŁY NADAWCA — klient pisze z własnego adresu. Kontakt bierzemy z nagłówka,
 *    lead dostaje wątek i od tej chwili korespondencja JEST historią leada. Dokładnie
 *    to samo, co robi ręczne „Oznacz jako lead".
 *
 *  • ROBOT FORMULARZA (adres w `form_mail_sources`) — mail przyszedł z wordpress@
 *    albo no-reply@, a jedyny prawdziwy kontakt do klienta stoi W TREŚCI. Tę ścieżkę
 *    obsługuje [FormMailLeadProcessor]: on umie odczytać kontakt z treści, sprowadzić
 *    markę do katalogu, policzyć zgłoszenia w źródle i wpisać się do dziennika, który
 *    czyta podgląd wątku. Wywołujemy go zamiast przepisywać — dwie kopie tej samej
 *    logiki budowania leada rozjechałyby się przy pierwszej poprawce.
 *
 * Automatem pozostajemy tylko my: [pl.detailing.crm.leads.formmail.FormMailAutoLeadListener]
 * przy włączonej fladze nie działa, więc o żadnym mailu nie decydują dwa mechanizmy naraz.
 */
@Service
class AutoLeadProcessor(
    private val classifier: LeadMessageClassifier,
    private val classificationRepository: LeadMessageClassificationRepository,
    private val rateLimiter: LeadClassificationRateLimiter,
    private val formMailLeadProcessor: FormMailLeadProcessor,
    private val leadRepository: LeadRepository,
    private val threadRepository: CommThreadRepository,
    private val customerRepository: CustomerRepository,
    private val statusService: LeadStatusService,
    private val soleUserResolver: SoleUserResolver,
    private val eventPublisher: ApplicationEventPublisher,
    private val transactionTemplate: TransactionTemplate,
    @Value("\${crm.ai.lead-classification.min-confidence:0.7}") private val minConfidence: Double
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun process(
        message: CommMessageEntity,
        thread: CommThreadEntity,
        formSource: FormMailSourceEntity?
    ): AutoLeadResult {
        classificationRepository.findByMessageId(message.id)?.let {
            return AutoLeadResult.AlreadyProcessed(it.leadId)
        }

        val body = message.bodyTextClean?.takeIf { it.isNotBlank() }
            ?: message.bodyText?.takeIf { it.isNotBlank() }
            ?: return record(message, thread, STATUS_SKIPPED, reason = "Wiadomość bez treści tekstowej")

        // Ostatni próg przed pierwszym tokenem. Wyżej odsiewamy za darmo (flaga, nagłówki,
        // wątek z leadem), tutaj kończy się to, co możemy przewidzieć.
        if (!rateLimiter.tryConsume(message.studioId)) {
            return record(message, thread, STATUS_SKIPPED, reason = "Dzienny limit klasyfikacji wyczerpany")
        }

        val verdict = runBlocking { classifier.classify(message.subject, body) }
            ?: return record(message, thread, STATUS_FAILED, reason = "Klasyfikacja LLM nie powiodła się")

        if (verdict.verdict == LeadClassificationVerdict.NOT_LEAD) {
            return record(
                message, thread, STATUS_REJECTED,
                classification = verdict,
                reason = "Model uznał wiadomość za niebędącą zapytaniem klienta"
            )
        }

        // Prompt każe zaniżać pewność przy wątpliwościach, a my ją tu egzekwujemy:
        // niepewny lead nie powstaje. Wiadomość zostaje w skrzynce i można ją oznaczyć
        // ręcznie — koszt przeoczenia jest odwracalny, koszt śmiecia w CRM-ie nie bardzo.
        if (verdict.confidence < minConfidence) {
            return record(
                message, thread, STATUS_REJECTED,
                classification = verdict,
                reason = "Pewność %.2f poniżej progu %.2f".format(verdict.confidence, minConfidence)
            )
        }

        return if (formSource != null) {
            createFromFormMail(message, thread, formSource, verdict)
        } else {
            createFromThread(message, thread, body, verdict)
        }
    }

    /**
     * Zwykły nadawca: lead z wątkiem, jak przy ręcznym oznaczeniu konwersacji.
     *
     * Status zawsze NEW — klasyfikujemy pierwszą wiadomość przychodzącą w wątku, więc
     * z definicji nikt jeszcze nie zdążył na nią odpisać.
     */
    private fun createFromThread(
        message: CommMessageEntity,
        thread: CommThreadEntity,
        body: String,
        verdict: LeadClassification
    ): AutoLeadResult = try {
        transactionTemplate.execute {
            // Wątek czytamy w transakcji od nowa: między klasyfikacją a zapisem minęły
            // sekundy rozmowy z modelem i ktoś mógł w tym czasie oznaczyć go ręcznie.
            val fresh = threadRepository.findById(thread.id).orElse(null)
            if (fresh?.leadId != null || leadRepository.findByThreadId(thread.id) != null) {
                // Ślad zostaje mimo braku leada: bez niego ponowny przebieg zapłaciłby
                // za przeczytanie tej samej wiadomości jeszcze raz.
                classificationRepository.save(
                    entity(message, thread, STATUS_SKIPPED, verdict, "Wątek zdążył zostać leadem")
                )
                return@execute AutoLeadResult.Skipped("Wątek zdążył zostać leadem")
            }

            val customer = customerRepository.findActiveByStudioIdAndEmail(
                message.studioId, thread.participantEmail
            )
            val assignee = soleUserResolver.resolveForStudio(message.studioId)

            val lead = LeadEntity(
                id = UUID.randomUUID(),
                studioId = message.studioId,
                source = LeadSource.EMAIL,
                status = LeadStatus.NEW,
                contactIdentifier = thread.participantEmail,
                customerName = thread.participantName
                    ?: customer?.let { listOfNotNull(it.firstName, it.lastName).joinToString(" ").ifBlank { null } },
                initialMessage = body.trim().take(MAX_INITIAL_MESSAGE),
                estimatedValue = 0,
                requiresVerification = false,
                vehicleBrand = null,
                vehicleModel = null,
                // Rozpoznanie auta rusza po zatwierdzeniu transakcji; do tego czasu
                // tabela ma pokazywać, że pracuje, a nie pustą komórkę.
                vehicleDetectionStatus = LeadVehicleDetectionStatus.PENDING,
                customerId = customer?.id,
                appointmentId = null,
                visitId = null,
                assignedUserId = assignee?.id,
                assignedUserName = assignee?.name,
                lostReason = null,
                stagnantAlertSentAt = null,
                threadId = thread.id,
                category = null,
                firstResponseAt = null
            )
            leadRepository.save(lead)
            statusService.recordCreation(lead, assignee?.id, assignee?.name ?: AUTOMAT_NAME)

            fresh?.let {
                it.leadId = lead.id
                threadRepository.save(it)
            }

            // Dziennik w tej samej transakcji co lead: unikalny indeks na message_id jest
            // kluczem idempotencji, więc równoległy przebieg wywróci się TUTAJ i wycofa
            // razem ze swoim leadem.
            classificationRepository.save(
                entity(message, thread, STATUS_CREATED, verdict, leadId = lead.id)
            )

            eventPublisher.publishEvent(
                NewLeadCreatedEvent(
                    source = this,
                    studioId = StudioId(message.studioId),
                    leadId = LeadId(lead.id),
                    leadSource = LeadSource.EMAIL,
                    contactIdentifier = lead.contactIdentifier,
                    customerName = lead.customerName,
                    estimatedValue = 0,
                    createdAt = Instant.now()
                )
            )
            eventPublisher.publishEvent(LeadThreadAttachedEvent(leadId = lead.id, threadId = thread.id))

            log.info(
                "[LEAD_CLASSIFY] Wiadomość {} od {} utworzyła leada {} (pewność {})",
                message.id, thread.participantEmail, lead.id, verdict.confidence
            )
            AutoLeadResult.Created(lead.id)
        }!!
    } catch (e: DataIntegrityViolationException) {
        log.debug("[LEAD_CLASSIFY] Wiadomość {} przetworzona równolegle — czytam istniejący wpis", message.id)
        AutoLeadResult.AlreadyProcessed(classificationRepository.findByMessageId(message.id)?.leadId)
    }

    /**
     * Robot formularza: leada buduje [FormMailLeadProcessor], bo tylko on umie wyciągnąć
     * kontakt klienta z TREŚCI maila — adres nadawcy jest tu bezużyteczny.
     *
     * Nasz dziennik zapisuje sam werdykt. Wpis o samym leadzie prowadzi tamten procesor
     * w swoim dzienniku i to on karmi licznik zgłoszeń przy źródle oraz oznaczenia
     * w podglądzie wątku — nie ma po co ich dublować.
     */
    private fun createFromFormMail(
        message: CommMessageEntity,
        thread: CommThreadEntity,
        source: FormMailSourceEntity,
        verdict: LeadClassification
    ): AutoLeadResult {
        val result = runCatching { formMailLeadProcessor.process(source, message) }
            .getOrElse {
                log.warn("[LEAD_CLASSIFY] Budowa leada z formularza dla {} zawiodła: {}", message.id, it.message)
                return record(message, thread, STATUS_FAILED, verdict, "Odczyt danych z formularza nie powiódł się")
            }

        return when (result) {
            is FormMailProcessResult.Created -> {
                journal(message, thread, STATUS_CREATED, verdict, leadId = result.leadId)
                log.info(
                    "[LEAD_CLASSIFY] Mail {} z formularza {} utworzył leada {} (pewność {})",
                    message.id, source.senderEmail, result.leadId, verdict.confidence
                )
                AutoLeadResult.Created(result.leadId)
            }
            is FormMailProcessResult.AlreadyProcessed -> {
                // Dziennik form-maila zna już ten mail. Jego leadId bywa NULL — tamten
                // przebieg mógł skończyć się odrzuceniem (formularz bez kontaktu klienta),
                // a nie leadem. Stąd zapis do dziennika bez wnioskowania, że lead powstał.
                journal(message, thread, STATUS_CREATED, verdict, leadId = result.leadId)
                AutoLeadResult.AlreadyProcessed(result.leadId)
            }
            is FormMailProcessResult.Rejected ->
                record(message, thread, STATUS_REJECTED, verdict, reason = result.reason)
            is FormMailProcessResult.Failed ->
                record(message, thread, STATUS_FAILED, verdict, reason = result.reason)
        }
    }

    /**
     * Wpis do dziennika bez własnego leada. Osobna, mała transakcja: ślad „przyszło,
     * ale nie zostało leadem" ma zostać niezależnie od tego, co zawiodło — i to on
     * pilnuje, żeby przy kolejnym przebiegu nie zapłacić za tę samą klasyfikację drugi raz.
     */
    private fun record(
        message: CommMessageEntity,
        thread: CommThreadEntity,
        status: String,
        classification: LeadClassification? = null,
        reason: String? = null
    ): AutoLeadResult {
        val conflict = journal(message, thread, status, classification, reason)
        if (conflict != null) return conflict

        log.debug(
            "[LEAD_CLASSIFY] Wiadomość {}: {} — {} ({})",
            message.id, status, reason ?: "-", classification?.reasoning ?: "-"
        )
        return when (status) {
            STATUS_FAILED -> AutoLeadResult.Failed(reason.orEmpty())
            STATUS_SKIPPED -> AutoLeadResult.Skipped(reason.orEmpty())
            else -> AutoLeadResult.Rejected(reason.orEmpty())
        }
    }

    /**
     * Sam zapis do dziennika, we własnej małej transakcji.
     *
     * @return [AutoLeadResult.AlreadyProcessed], gdy równoległy przebieg zdążył wpisać
     *   tę wiadomość przed nami (wywrócił nas unikalny indeks na message_id); null,
     *   gdy zapis się powiódł.
     */
    private fun journal(
        message: CommMessageEntity,
        thread: CommThreadEntity,
        status: String,
        classification: LeadClassification?,
        reason: String? = null,
        leadId: UUID? = null
    ): AutoLeadResult.AlreadyProcessed? = try {
        transactionTemplate.execute {
            classificationRepository.save(entity(message, thread, status, classification, reason, leadId))
        }
        null
    } catch (e: DataIntegrityViolationException) {
        AutoLeadResult.AlreadyProcessed(classificationRepository.findByMessageId(message.id)?.leadId)
    }

    private fun entity(
        message: CommMessageEntity,
        thread: CommThreadEntity,
        status: String,
        classification: LeadClassification?,
        reason: String? = null,
        leadId: UUID? = null
    ) = LeadMessageClassificationEntity(
        studioId = message.studioId,
        messageId = message.id,
        threadId = thread.id,
        status = status,
        verdict = classification?.verdict?.name,
        confidence = classification?.let { BigDecimal.valueOf(it.confidence).setScale(2, RoundingMode.HALF_UP) },
        reasoning = classification?.reasoning,
        reason = reason?.take(300),
        // Nazwa modelu ma sens tylko tam, gdzie model faktycznie odpowiedział.
        model = classification?.let { classifier.modelName() },
        leadId = leadId
    )

    companion object {
        const val STATUS_CREATED = "CREATED"
        const val STATUS_REJECTED = "REJECTED"
        const val STATUS_FAILED = "FAILED"
        const val STATUS_SKIPPED = "SKIPPED"

        /** Autor wpisu w historii statusów, gdy studio nie ma jednoznacznego właściciela. */
        private const val AUTOMAT_NAME = "Automatyczne rozpoznanie"

        /**
         * Sufit „pierwszej wiadomości" — ta treść jedzie w każdej odpowiedzi listy leadów,
         * a panel i tak pokazuje wycinek. Ta sama wartość co w ręcznym oznaczaniu wątku.
         */
        private const val MAX_INITIAL_MESSAGE = 2_000
    }
}
