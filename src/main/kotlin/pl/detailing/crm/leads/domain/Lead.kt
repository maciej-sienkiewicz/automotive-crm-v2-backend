package pl.detailing.crm.leads.domain

import pl.detailing.crm.shared.AppointmentId
import pl.detailing.crm.shared.CustomerId
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.LeadStatus
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.VisitId
import java.time.Instant
import java.util.UUID

/**
 * Closed dictionary of loss reasons. A closed list is the price of the loss analytics
 * working at all — a free-text field cannot be aggregated into "why do we lose".
 */
enum class LeadLostReason(val label: String) {
    TOO_EXPENSIVE("Za drogo"),
    NO_AVAILABILITY("Brak wolnego terminu"),
    NO_RESPONSE("Klient przestał odpowiadać"),
    CHOSE_COMPETITOR("Wybrał konkurencję"),
    OUT_OF_SCOPE("Poza zakresem usług"),
    SPAM("Spam / nie było zapytaniem"),
    OTHER("Inny powód")
}

/** What the client is asking about — the "o co pytają" axis of the analytics. */
enum class LeadCategory(val label: String) {
    CERAMIC_COATING("Powłoka ceramiczna"),
    PPF_WRAP("Folia PPF / oklejanie"),
    CORRECTION_POLISH("Korekta lakieru"),
    INTERIOR("Detailing wnętrza"),
    WASH_MAINTENANCE("Mycie i pielęgnacja"),
    FULL_DETAILING("Pełny detailing"),
    OTHER("Inne")
}

/**
 * Domain model for a sales lead. E-mail leads point at their conversation
 * ([threadId]); the message history is simply the thread — no copying, no syncing.
 */
data class Lead(
    val id: LeadId,
    val studioId: StudioId,
    val source: LeadSource,
    val status: LeadStatus,
    val contactIdentifier: String,
    val customerName: String?,
    val initialMessage: String?,
    /** Suma pozycji usługowych w groszach; 0, gdy nic jeszcze nie wyceniono. */
    val estimatedValue: Long,
    val requiresVerification: Boolean,
    val vehicleBrand: String? = null,
    val vehicleModel: String? = null,
    val customerId: CustomerId? = null,
    val appointmentId: AppointmentId? = null,
    val visitId: VisitId? = null,
    val assignedUserId: UserId? = null,
    val assignedUserName: String? = null,
    val lostReason: String? = null,
    val stagnantAlertSentAt: Instant? = null,
    val threadId: UUID? = null,
    val category: LeadCategory? = null,
    val lostReasonCode: LeadLostReason? = null,
    val firstResponseAt: Instant? = null,
    val closedAt: Instant? = null,
    val createdAt: Instant,
    val updatedAt: Instant
)

/** One priced service on a lead; the price is frozen at assignment time. */
data class LeadServiceItem(
    val id: UUID,
    val leadId: LeadId,
    val serviceId: UUID?,
    val name: String,
    val priceGross: Long,
    val quantity: Int
) {
    val totalGross: Long get() = priceGross * quantity
}
