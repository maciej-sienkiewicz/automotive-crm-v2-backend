package pl.detailing.crm.leads.formmail

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.comms.infrastructure.CommMessageEntity
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.leads.create.SoleUserResolver
import pl.detailing.crm.leads.domain.LeadVehicleDetectionStatus
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.intake.normalizeFieldName
import pl.detailing.crm.leads.tags.LeadTagCatalogService
import pl.detailing.crm.leads.update.LeadStatusService
import pl.detailing.crm.leads.update.LeadTagService
import pl.detailing.crm.leads.vehicle.LeadTextAttachedEvent
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.NewLeadCreatedEvent
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.vehicle.VehicleCatalogMatcher
import java.time.Instant
import java.util.UUID

sealed interface FormMailProcessResult {
    data class Created(val leadId: UUID) : FormMailProcessResult
    data class Rejected(val reason: String) : FormMailProcessResult
    data class Failed(val reason: String) : FormMailProcessResult
    /** Dziennik zna już ten mail — nic do zrobienia, wynik z pierwszego przebiegu. */
    data class AlreadyProcessed(val leadId: UUID?) : FormMailProcessResult
}

/**
 * Zamienia jeden mail z formularza na leada: odczyt LLM-em, walidacja kontaktu,
 * zapis leada i wpis do dziennika.
 *
 * Wspólny rdzeń dwóch ścieżek — ręcznego „Oznacz jako lead z formularza" i
 * automatu dla kolejnych maili z oznaczonego adresu. Obie mają robić dokładnie
 * to samo, więc robi to jeden kod.
 *
 * Kolejność jest nieprzypadkowa: odczyt LLM-em trwa sekundy i stoi PRZED
 * transakcją, a nie w niej — trzymanie otwartej transakcji przez czas rozmowy
 * z modelem blokowałoby pulę połączeń dokładnie wtedy, gdy sync ma najwięcej
 * do roboty.
 */
@Service
class FormMailLeadProcessor(
    private val extractionService: FormMailExtractionService,
    private val extractionRepository: FormMailExtractionRepository,
    private val sourceRepository: FormMailSourceRepository,
    private val leadRepository: LeadRepository,
    private val customerRepository: CustomerRepository,
    private val statusService: LeadStatusService,
    private val tagService: LeadTagService,
    private val tagCatalog: LeadTagCatalogService,
    private val soleUserResolver: SoleUserResolver,
    private val catalogMatcher: VehicleCatalogMatcher,
    private val eventPublisher: ApplicationEventPublisher,
    private val transactionTemplate: TransactionTemplate
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun process(source: FormMailSourceEntity, message: CommMessageEntity): FormMailProcessResult {
        extractionRepository.findByMessageId(message.id)?.let {
            return FormMailProcessResult.AlreadyProcessed(it.leadId)
        }

        val body = message.bodyTextClean?.takeIf { it.isNotBlank() }
            ?: message.bodyText?.takeIf { it.isNotBlank() }
        if (body == null) {
            return record(source, message, STATUS_REJECTED, "Mail bez treści tekstowej")
        }

        val extracted = runBlocking { extractionService.extract(message.subject, body) }
            ?: return record(source, message, STATUS_FAILED, "Odczyt LLM nie powiódł się")

        val contact = extracted.email ?: extracted.phone
            ?: return record(
                source, message, STATUS_REJECTED,
                "W treści nie znaleziono adresu e-mail ani telefonu klienta"
            )

        // Marka z formularza bywa wpisana ręcznie — tabela leadów i wyszukiwanie
        // działają tylko na wartościach z katalogu, jak przy webhookach formularzy.
        val vehicle = if (extracted.vehicleBrand.isNullOrBlank() && extracted.vehicleModel.isNullOrBlank()) {
            VehicleCatalogMatcher.Match(null, null)
        } else {
            runBlocking { catalogMatcher.resolve(extracted.vehicleBrand, extracted.vehicleModel) }
        }

        val initialMessage = composeMessage(extracted, body)
        // Auto doczytujemy z treści tylko, gdy formularz go nie podał — inaczej
        // płacilibyśmy za pytanie o coś, co już wiemy.
        val needsVehicleExtraction = vehicle.brand == null && initialMessage.isNotBlank()

        return try {
            transactionTemplate.execute {
                val customer = extracted.email
                    ?.let { customerRepository.findActiveByStudioIdAndEmail(source.studioId, it) }
                val assignee = soleUserResolver.resolveForStudio(source.studioId)

                val lead = LeadEntity(
                    id = UUID.randomUUID(),
                    studioId = source.studioId,
                    source = LeadSource.FORM,
                    status = LeadStatus.NEW,
                    contactIdentifier = contact,
                    customerName = extracted.customerName
                        ?: customer?.let {
                            listOfNotNull(it.firstName, it.lastName).joinToString(" ").ifBlank { null }
                        },
                    initialMessage = initialMessage.take(MAX_MESSAGE),
                    estimatedValue = 0,
                    requiresVerification = false,
                    vehicleBrand = vehicle.brand,
                    vehicleModel = vehicle.model,
                    vehicleDetectionStatus = if (needsVehicleExtraction) {
                        LeadVehicleDetectionStatus.PENDING
                    } else {
                        LeadVehicleDetectionStatus.DONE
                    },
                    customerId = customer?.id,
                    appointmentId = null,
                    visitId = null,
                    assignedUserId = assignee?.id,
                    assignedUserName = assignee?.name,
                    lostReason = null,
                    stagnantAlertSentAt = null,
                    // Świadomie bez wątku: wątek należy do robota formularza, a nie do
                    // klienta, i potrafi zbierać zgłoszenia wielu różnych osób. Odpowiedź
                    // na leada ma iść na kontakt z treści, nie na no-reply.
                    threadId = null,
                    category = null,
                    firstResponseAt = null
                )
                leadRepository.save(lead)
                statusService.recordCreation(lead, assignee?.id, assignee?.name ?: "Formularz ze strony")

                val tags = resolveTags(StudioId(source.studioId), extracted.service)
                if (tags.isNotEmpty()) tagService.replaceTags(lead.id, tags)

                // Dziennik w tej samej transakcji co lead: unikalny indeks na message_id
                // jest kluczem idempotencji, więc równoległy przebieg wywróci się tutaj
                // i cała jego transakcja (razem z drugim leadem) zostanie wycofana.
                extractionRepository.save(
                    FormMailExtractionEntity(
                        studioId = source.studioId,
                        sourceId = source.id,
                        messageId = message.id,
                        status = STATUS_CREATED,
                        leadId = lead.id
                    )
                )
                sourceRepository.findById(source.id).ifPresent { fresh ->
                    fresh.leadCount += 1
                    fresh.lastLeadAt = Instant.now()
                    sourceRepository.save(fresh)
                }

                eventPublisher.publishEvent(
                    NewLeadCreatedEvent(
                        source = this,
                        studioId = StudioId(source.studioId),
                        leadId = LeadId(lead.id),
                        leadSource = LeadSource.FORM,
                        contactIdentifier = lead.contactIdentifier,
                        customerName = lead.customerName,
                        estimatedValue = 0,
                        createdAt = Instant.now()
                    )
                )
                if (needsVehicleExtraction) {
                    eventPublisher.publishEvent(LeadTextAttachedEvent(leadId = lead.id, text = initialMessage))
                }

                log.info(
                    "[FORM_MAIL] Mail {} od {} utworzył leada {} ({})",
                    message.id, source.senderEmail, lead.id, contact
                )
                FormMailProcessResult.Created(lead.id)
            }!!
        } catch (e: DataIntegrityViolationException) {
            // Wyścig dwóch przebiegów o ten sam mail — wygrał pierwszy, czytamy jego wynik.
            log.debug("[FORM_MAIL] Mail {} przetworzony równolegle — czytam istniejący wpis", message.id)
            FormMailProcessResult.AlreadyProcessed(extractionRepository.findByMessageId(message.id)?.leadId)
        }
    }

    /**
     * Wpis do dziennika bez leada — odrzucenie albo awaria. Własna, mała transakcja:
     * ślad „przyszło, ale poległo" ma zostać niezależnie od tego, co zawiodło.
     */
    private fun record(
        source: FormMailSourceEntity,
        message: CommMessageEntity,
        status: String,
        reason: String
    ): FormMailProcessResult {
        try {
            transactionTemplate.execute {
                extractionRepository.save(
                    FormMailExtractionEntity(
                        studioId = source.studioId,
                        sourceId = source.id,
                        messageId = message.id,
                        status = status,
                        reason = reason.take(300)
                    )
                )
            }
        } catch (e: DataIntegrityViolationException) {
            return FormMailProcessResult.AlreadyProcessed(
                extractionRepository.findByMessageId(message.id)?.leadId
            )
        }
        log.info("[FORM_MAIL] Mail {} od {}: {} — {}", message.id, source.senderEmail, status, reason)
        return if (status == STATUS_FAILED) {
            FormMailProcessResult.Failed(reason)
        } else {
            FormMailProcessResult.Rejected(reason)
        }
    }

    /**
     * Treść zapytania: wiadomość klienta, a pod nią to, co formularz wiedział ponadto.
     * Lead z maila formularza ma wiedzieć tyle samo co ten mail — nic nie ginie.
     */
    private fun composeMessage(extracted: ExtractedFormLead, rawBody: String): String {
        val parts = mutableListOf<String>()
        extracted.message?.let(parts::add)

        val details = buildList {
            extracted.service?.let { add("Usługa: $it") }
            val car = listOfNotNull(extracted.vehicleBrand, extracted.vehicleModel).joinToString(" ")
            if (car.isNotBlank()) add("Pojazd: $car")
            extracted.phone?.let { add("Telefon: $it") }
            extracted.email?.let { add("E-mail: $it") }
        }
        if (details.isNotEmpty()) parts += details.joinToString("\n")

        // Odczyt bez właściwej wiadomości — zostaje surowa treść maila, przycięta:
        // lepszy nieociosany oryginał niż lead, który nie mówi, czego klient chce.
        if (parts.isEmpty()) parts += rawBody.take(MAX_MESSAGE)

        return parts.joinToString("\n\n").trim()
    }

    /** Usługa z maila → tagi studia, po nazwie — jak przy webhookach formularzy. */
    private fun resolveTags(studioId: StudioId, service: String?): List<String> {
        if (service.isNullOrBlank()) return emptyList()
        val catalogue = tagCatalog.listActive(studioId)
        val codes = LinkedHashSet<String>()
        service.split(',', ';', '|').map { normalizeFieldName(it) }.filter { it.isNotBlank() }
            .forEach { chosen ->
                catalogue.firstOrNull { definition ->
                    val label = normalizeFieldName(definition.label)
                    label == chosen || label.contains(chosen) || chosen.contains(label)
                }?.let { codes += it.code }
            }
        return codes.toList()
    }

    companion object {
        const val STATUS_CREATED = "CREATED"
        const val STATUS_REJECTED = "REJECTED"
        const val STATUS_FAILED = "FAILED"

        private const val MAX_MESSAGE = 4000
    }
}
