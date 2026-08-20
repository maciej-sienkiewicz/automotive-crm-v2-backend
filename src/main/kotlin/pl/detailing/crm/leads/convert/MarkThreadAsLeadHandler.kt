package pl.detailing.crm.leads.convert

import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.comms.infrastructure.CommThreadRepository
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.comms.domain.CommDirection
import pl.detailing.crm.comms.infrastructure.CommMessageRepository
import pl.detailing.crm.leads.domain.LeadVehicleDetectionStatus
import pl.detailing.crm.leads.infrastructure.LeadEntity
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.update.LeadTagService
import pl.detailing.crm.leads.vehicle.LeadThreadAttachedEvent
import pl.detailing.crm.leads.update.LeadServiceItemsService
import pl.detailing.crm.leads.update.LeadServiceItemInput
import pl.detailing.crm.leads.update.LeadStatusService
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.NewLeadCreatedEvent
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.util.UUID

data class MarkThreadAsLeadCommand(
    val studioId: StudioId,
    val threadId: UUID,
    val userId: UUID,
    val userName: String,
    /** Kody tagów ze słownika studia, zwalidowane przez katalog przed wejściem tutaj. */
    val tags: List<String>,
    val services: List<LeadServiceItemInput>
)

data class MarkThreadAsLeadResult(
    val leadId: UUID,
    val estimatedValue: Long
)

/**
 * The one-click "Oznacz jako lead" action. Everything the system can know is filled
 * in automatically: contact from the thread, customer by e-mail match, first message
 * as context, value from the picked services. From here on the thread's messages ARE
 * the lead's history — no copying, no extra tracking.
 */
@Service
class MarkThreadAsLeadHandler(
    private val threadRepository: CommThreadRepository,
    private val leadRepository: LeadRepository,
    private val customerRepository: CustomerRepository,
    private val messageRepository: CommMessageRepository,
    private val serviceItems: LeadServiceItemsService,
    private val tagService: LeadTagService,
    private val statusService: LeadStatusService,
    private val eventPublisher: ApplicationEventPublisher
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /**
         * Sufit długości „pierwszej wiadomości". Kolumna jest typu TEXT, ale ta treść
         * jedzie w każdej odpowiedzi listy leadów — mail z wklejoną ofertą na trzy strony
         * obciążałby każde otwarcie widoku, a panel i tak pokazuje wycinek.
         */
        private const val MAX_INITIAL_MESSAGE_CHARS = 2_000
    }

    @Transactional
    fun handle(command: MarkThreadAsLeadCommand): MarkThreadAsLeadResult {
        val thread = threadRepository.findByIdAndStudioId(command.threadId, command.studioId.value)
            ?: throw NotFoundException("Nie znaleziono wątku")

        leadRepository.findByThreadId(thread.id)?.let {
            throw ConflictException("Ta konwersacja jest już leadem")
        }

        val customer = customerRepository.findActiveByStudioIdAndEmail(
            command.studioId.value, thread.participantEmail
        )

        val messages = messageRepository.findByThreadIdOrderBySentAtAsc(thread.id)

        // Kolejność zdarzeń bywa odwrotna: najpierw odpisujemy, potem orientujemy się,
        // że to lead. Status ma opisywać stan rozmowy, a nie moment kliknięcia, więc
        // odpowiedź, która już poszła, od razu ustawia „W kontakcie".
        val ourReply = messages.lastOrNull { it.direction == CommDirection.OUTBOUND }
        val initialStatus = if (ourReply != null) LeadStatus.IN_PROGRESS else LeadStatus.NEW

        // Pierwsza wiadomość klienta, a nie ostatnia w wątku. thread.lastSnippet, którym
        // pole było wypełniane wcześniej, opisuje najnowszą wiadomość — więc lead oznaczony
        // po wymianie kilku maili dostawał w „pierwszej wiadomości" naszą własną odpowiedź
        // („czy odpowiada Panu 2.09?") zamiast pytania, od którego wszystko się zaczęło.
        val firstInbound = messages.firstOrNull { it.direction == CommDirection.INBOUND }
        val initialMessage = (
            firstInbound?.bodyTextClean?.takeIf { it.isNotBlank() }
                ?: firstInbound?.bodyText?.takeIf { it.isNotBlank() }
                // Wątek bez wiadomości przychodzącej (rozmowa zaczęta przez nas) nie ma
                // „pytania klienta" — wtedy snippet wątku to najlepsze, co mamy.
                ?: thread.lastSnippet
            )?.trim()?.take(MAX_INITIAL_MESSAGE_CHARS)

        val lead = LeadEntity(
            id = UUID.randomUUID(),
            studioId = command.studioId.value,
            source = LeadSource.EMAIL,
            status = initialStatus,
            contactIdentifier = thread.participantEmail,
            customerName = thread.participantName
                ?: customer?.let { listOfNotNull(it.firstName, it.lastName).joinToString(" ").ifBlank { null } },
            initialMessage = initialMessage,
            estimatedValue = 0,
            requiresVerification = false,
            vehicleBrand = null,
            vehicleModel = null,
            // Rozpoznanie auta rusza po zatwierdzeniu transakcji; do tego czasu tabela
            // ma pokazywać, że pracuje, a nie pustą komórkę nie do odróżnienia od braku.
            vehicleDetectionStatus = LeadVehicleDetectionStatus.PENDING,
            customerId = customer?.id,
            appointmentId = null,
            visitId = null,
            assignedUserId = command.userId,
            assignedUserName = command.userName,
            lostReason = null,
            stagnantAlertSentAt = null,
            threadId = thread.id,
            category = null,
            firstResponseAt = ourReply?.sentAt
        )
        leadRepository.save(lead)
        statusService.recordCreation(lead, command.userId, command.userName)

        tagService.replaceTags(lead.id, command.tags)

        val total = if (command.services.isNotEmpty()) {
            serviceItems.replaceItems(lead, command.services)
        } else 0L

        thread.leadId = lead.id
        threadRepository.save(thread)

        eventPublisher.publishEvent(
            NewLeadCreatedEvent(
                source = this,
                studioId = command.studioId,
                leadId = LeadId(lead.id),
                leadSource = LeadSource.EMAIL,
                contactIdentifier = lead.contactIdentifier,
                customerName = lead.customerName,
                estimatedValue = total,
                createdAt = Instant.now()
            )
        )
        // Marka i model auta doczytają się z korespondencji po zatwierdzeniu
        // transakcji — rozpoznanie idzie do LLM-a i nie ma prawa opóźniać kliknięcia.
        eventPublisher.publishEvent(LeadThreadAttachedEvent(leadId = lead.id, threadId = thread.id))

        log.info(
            "[LEADS] Thread {} marked as lead {} (status={}, value={} gr, tags={})",
            thread.id, lead.id, initialStatus, total, command.tags.size
        )
        return MarkThreadAsLeadResult(leadId = lead.id, estimatedValue = total)
    }
}
