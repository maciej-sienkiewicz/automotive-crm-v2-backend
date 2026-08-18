package pl.detailing.crm.comms.insights

import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.appointment.infrastructure.AppointmentRepository
import pl.detailing.crm.comms.infrastructure.CommThreadRepository
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.leads.infrastructure.LeadRepository
import pl.detailing.crm.leads.infrastructure.LeadServiceItemRepository
import pl.detailing.crm.leads.query.LeadDto
import pl.detailing.crm.leads.query.toDto
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant

data class ContactInsightsDto(
    val email: String,
    /** Dopasowany klient z kartoteki; null = nowy kontakt. */
    val customer: InsightsCustomerDto?,
    /** Poprzednie konwersacje z tym adresem (bez bieżącej), najnowsze pierwsze. */
    val previousThreads: List<InsightsThreadDto>,
    /** Leady powiązane z tym adresem, najnowsze pierwsze. */
    val leads: List<LeadDto>,
    /** Nadchodzące rezerwacje — to one zmieniają rozmowę. */
    val upcomingAppointments: List<InsightsAppointmentDto>,
    /** Ostatnie odbyte wizyty. */
    val pastAppointments: List<InsightsAppointmentDto>
)

data class InsightsCustomerDto(
    val id: String,
    val name: String?,
    val phone: String?,
    /** Liczba ukończonych wizyt klienta — "ile razy u nas był". */
    val completedVisitCount: Int,
    /** Suma brutto ukończonych wizyt w groszach — "ile u nas zostawił". */
    val totalSpentGross: Long
)

data class InsightsThreadDto(
    val id: String,
    val subject: String?,
    val lastMessageAt: Instant,
    val snippet: String?,
    val leadId: String?
)

data class InsightsAppointmentDto(
    val id: String,
    val title: String?,
    val startDateTime: Instant,
    val endDateTime: Instant,
    val status: String
)

/**
 * The context panel behind an open message: does this address know us, what did they
 * ask before, do they have reservations. One indexed lookup per source — the panel
 * has to feel instant, so nothing here may scan.
 */
@Service
class GetContactInsightsHandler(
    private val customerRepository: CustomerRepository,
    private val threadRepository: CommThreadRepository,
    private val leadRepository: LeadRepository,
    private val leadItemRepository: LeadServiceItemRepository,
    private val appointmentRepository: AppointmentRepository,
    private val visitRepository: VisitRepository
) {

    @Transactional(readOnly = true)
    fun handle(studioId: StudioId, rawEmail: String, excludeThreadId: String?): ContactInsightsDto {
        val email = rawEmail.trim().lowercase()

        val customer = customerRepository.findActiveByStudioIdAndEmail(studioId.value, email)

        val threads = threadRepository
            .findByStudioIdAndParticipantEmailOrderByLastMessageAtDesc(
                studioId.value, email, PageRequest.of(0, MAX_THREADS + 1)
            )
            .filter { it.id.toString() != excludeThreadId }
            .take(MAX_THREADS)

        val leads = leadRepository
            .findByStudioIdAndContactIdentifierOrderByCreatedAtDesc(studioId.value, email)
            .take(MAX_LEADS)
        val itemsByLead = leadItemRepository.findByLeadIdIn(leads.map { it.id }).groupBy { it.leadId }

        val now = Instant.now()
        val appointments = customer
            ?.let { appointmentRepository.findByStudioIdAndCustomerId(studioId.value, it.id) }
            .orEmpty()
        val (upcoming, past) = appointments.partition { it.endDateTime.isAfter(now) }

        // Lifetime stats of the known client — the two numbers that change how you
        // answer the mail: how many times they visited and how much they spent.
        val completedVisits = customer?.let {
            visitRepository.findCompletedByCustomerIdAndDateRange(
                customerId = it.id,
                studioId = studioId.value,
                from = Instant.EPOCH,
                to = now
            )
        }.orEmpty()

        return ContactInsightsDto(
            email = email,
            customer = customer?.let {
                InsightsCustomerDto(
                    id = it.id.toString(),
                    name = listOfNotNull(it.firstName, it.lastName).joinToString(" ").ifBlank { null },
                    phone = it.phone,
                    completedVisitCount = completedVisits.size,
                    totalSpentGross = completedVisits.sumOf { visit ->
                        visit.serviceItems.sumOf { item -> item.finalPriceGross }
                    }
                )
            },
            previousThreads = threads.map {
                InsightsThreadDto(
                    id = it.id.toString(),
                    subject = it.subject,
                    lastMessageAt = it.lastMessageAt,
                    snippet = it.lastSnippet,
                    leadId = it.leadId?.toString()
                )
            },
            leads = leads.map { it.toDto(itemsByLead[it.id].orEmpty()) },
            upcomingAppointments = upcoming
                .sortedBy { it.startDateTime }
                .take(MAX_APPOINTMENTS)
                .map { it.toInsightsDto() },
            pastAppointments = past
                .take(MAX_APPOINTMENTS)
                .map { it.toInsightsDto() }
        )
    }

    private fun pl.detailing.crm.appointment.infrastructure.AppointmentEntity.toInsightsDto() =
        InsightsAppointmentDto(
            id = id.toString(),
            title = appointmentTitle,
            startDateTime = startDateTime,
            endDateTime = endDateTime,
            status = status.name
        )

    private companion object {
        const val MAX_THREADS = 5
        const val MAX_LEADS = 5
        const val MAX_APPOINTMENTS = 5
    }
}
