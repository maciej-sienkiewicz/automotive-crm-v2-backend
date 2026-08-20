package pl.detailing.crm.leads.intake

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.leads.create.SoleUserResolver
import pl.detailing.crm.leads.domain.LeadVehicleDetectionStatus
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
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

sealed interface FormSubmissionResult {
    data class Created(val leadId: UUID) : FormSubmissionResult
    data class Rejected(val reason: String) : FormSubmissionResult
}

/**
 * Zamienia zgłoszenie z formularza na leada.
 *
 * Trzy zasady, które trzymają tę ścieżkę przy życiu, gdy formularz wygląda inaczej,
 * niż zakładaliśmy:
 *
 *  1. WYMAGAMY JEDNEJ RZECZY — sposobu kontaktu. Bez adresu albo telefonu lead jest
 *     bezużyteczny: nie ma do kogo napisać. Wszystko inne jest opcjonalne.
 *  2. NIC NIE GINIE. Pola, których słownik nie rozpoznał, dopisujemy do treści
 *     zapytania. Lead z formularza ma wiedzieć tyle samo co mail, który zastępuje.
 *  3. ODPOWIADAMY 200 TAK CZĘSTO, JAK SIĘ DA. Wtyczki formularzy traktują błąd HTTP
 *     jako awarię i potrafią pokazać go klientowi na stronie albo przestać wysyłać.
 *     Duplikat czy nierozpoznany kształt to nasz problem, nie problem klienta, który
 *     właśnie wysłał zapytanie — dlatego lądują w dzienniku, a nie w kodzie błędu.
 */
@Service
class HandleFormSubmissionHandler(
    private val leadRepository: LeadRepository,
    private val customerRepository: CustomerRepository,
    private val statusService: LeadStatusService,
    private val tagService: LeadTagService,
    private val tagCatalog: LeadTagCatalogService,
    private val soleUserResolver: SoleUserResolver,
    private val catalogMatcher: VehicleCatalogMatcher,
    private val eventPublisher: ApplicationEventPublisher
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun handle(
        webhook: LeadIntakeWebhookEntity,
        form: MappedForm,
        defaultTagCodes: List<String>
    ): FormSubmissionResult {
        val studioId = StudioId(webhook.studioId)
        val email = form[LeadFormField.EMAIL]?.trim()?.lowercase()?.takeIf { it.contains('@') }
        val phone = form[LeadFormField.PHONE]?.trim()?.takeIf { it.any(Char::isDigit) }
        val contact = email ?: phone
            ?: return FormSubmissionResult.Rejected(
                "Zgłoszenie bez adresu e-mail i bez telefonu — nie ma jak odpowiedzieć"
            )

        val customer = email?.let { customerRepository.findActiveByStudioIdAndEmail(webhook.studioId, it) }
        val assignee = soleUserResolver.resolveForStudio(webhook.studioId)

        val rawBrand = form[LeadFormField.VEHICLE_BRAND]
        val rawModel = form[LeadFormField.VEHICLE_MODEL]
        // Marka z formularza bywa wpisana ręcznie („bmw x5”, „mercedes benz”), a tabela
        // leadów i wyszukiwanie działają tylko na wartościach z katalogu.
        val vehicle = if (rawBrand.isNullOrBlank() && rawModel.isNullOrBlank()) {
            VehicleCatalogMatcher.Match(null, null)
        } else {
            runBlocking { catalogMatcher.resolve(rawBrand, rawModel) }
        }

        val message = composeMessage(form)
        // Auto rozpoznajemy z treści tylko wtedy, gdy formularz go nie podał — inaczej
        // płacilibyśmy za zapytanie do modelu o coś, co klient już wpisał.
        val needsExtraction = vehicle.brand == null && message.isNotBlank()

        val lead = LeadEntity(
            id = UUID.randomUUID(),
            studioId = webhook.studioId,
            source = LeadSource.FORM,
            status = LeadStatus.NEW,
            contactIdentifier = contact,
            customerName = form[LeadFormField.NAME]?.trim()?.takeIf { it.isNotBlank() }
                ?: form[LeadFormField.COMPANY]?.trim()?.takeIf { it.isNotBlank() }
                ?: customer?.let { listOfNotNull(it.firstName, it.lastName).joinToString(" ").ifBlank { null } },
            initialMessage = message.take(MAX_MESSAGE),
            estimatedValue = 0,
            requiresVerification = false,
            vehicleBrand = vehicle.brand,
            vehicleModel = vehicle.model,
            vehicleDetectionStatus = if (needsExtraction) {
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
            threadId = null,
            category = null,
            firstResponseAt = null
        )
        leadRepository.save(lead)
        statusService.recordCreation(lead, assignee?.id, assignee?.name ?: webhook.name)

        val tags = resolveTags(studioId, form[LeadFormField.SERVICE], defaultTagCodes)
        if (tags.isNotEmpty()) tagService.replaceTags(lead.id, tags)

        eventPublisher.publishEvent(
            NewLeadCreatedEvent(
                source = this,
                studioId = studioId,
                leadId = LeadId(lead.id),
                leadSource = LeadSource.FORM,
                contactIdentifier = lead.contactIdentifier,
                customerName = lead.customerName,
                estimatedValue = 0,
                createdAt = Instant.now()
            )
        )
        if (needsExtraction) {
            eventPublisher.publishEvent(LeadTextAttachedEvent(leadId = lead.id, text = message))
        }

        log.info(
            "[LEAD_INTAKE] Formularz {} utworzył leada {} ({}), tagów: {}",
            webhook.name, lead.id, contact, tags.size
        )
        return FormSubmissionResult.Created(lead.id)
    }

    /**
     * Treść zapytania: właściwa wiadomość, a pod nią wszystko, czego słownik nie
     * rozpoznał — dosłownie, w kolejności z formularza. To jest ta część, która
     * decyduje, czy lead z formularza jest tak samo użyteczny jak mail.
     */
    private fun composeMessage(form: MappedForm): String {
        val parts = mutableListOf<String>()
        form[LeadFormField.MESSAGE]?.takeIf { it.isNotBlank() }?.let(parts::add)

        val details = buildList {
            form[LeadFormField.SERVICE]?.let { add("Usługa: $it") }
            val car = listOfNotNull(
                form[LeadFormField.VEHICLE_BRAND],
                form[LeadFormField.VEHICLE_MODEL],
                form[LeadFormField.VEHICLE_YEAR]?.let { "($it)" }
            ).joinToString(" ")
            if (car.isNotBlank()) add("Pojazd: $car")
            form[LeadFormField.COMPANY]?.let { add("Firma: $it") }
            form[LeadFormField.PHONE]?.let { add("Telefon: $it") }
            addAll(form.leftovers.map { "${it.label}: ${it.value}" })
        }
        if (details.isNotEmpty()) parts += details.joinToString("\n")

        return parts.joinToString("\n\n").trim()
    }

    /**
     * Usługa z formularza → tagi studia. Dopasowujemy po nazwie tagu, bo w formularzu
     * stoi „Powłoka ceramiczna", a nie „CERAMIC_COATING". Czego nie znajdziemy, to
     * i tak zostaje w treści zapytania, więc nic nie ginie — po prostu nie liczy się
     * w statystykach, dopóki ktoś nie doda pasującego tagu.
     */
    private fun resolveTags(studioId: StudioId, service: String?, defaults: List<String>): List<String> {
        val codes = LinkedHashSet(defaults)
        if (!service.isNullOrBlank()) {
            val catalogue = tagCatalog.listActive(studioId)
            // Pole wielokrotnego wyboru przychodzi jako „Ceramika, PPF”.
            service.split(',', ';', '|').map { normalizeFieldName(it) }.filter { it.isNotBlank() }
                .forEach { chosen ->
                    catalogue.firstOrNull { definition ->
                        val label = normalizeFieldName(definition.label)
                        label == chosen || label.contains(chosen) || chosen.contains(label)
                    }?.let { codes += it.code }
                }
        }
        return codes.toList()
    }

    private companion object {
        const val MAX_MESSAGE = 4000
    }
}
