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
/**
 * Dlaczego zapytanie nie zamieniło się w zlecenie.
 *
 * [countsAsLoss] rozdziela dwie rzeczy, które wyglądają tak samo w bazie i zupełnie
 * inaczej w rachunku. Zapytanie, którego SAMI nie chcieliśmy, oraz zapytanie, które
 * nigdy nie było zapytaniem, nie są utraconymi pieniędzmi — nigdy nie były nasze.
 * Wrzucone do sumy strat kazałyby właścicielowi ścigać przychód, którego świadomie
 * nie chciał, i psuły statystykę tym mocniej, im lepiej kwalifikuje leady.
 *
 * „Odłożył decyzję" też nie jest stratą, tylko innym powodem: ten klient wciąż może
 * wrócić i pokazanie go w kolumnie strat zamyka sprawę, która jest otwarta.
 */
enum class LeadLostReason(val label: String, val countsAsLoss: Boolean = true) {
    // ── Straty: pieniądze, które mogliśmy mieć ──────────────────────────────
    TOO_EXPENSIVE("Za drogo"),
    NO_AVAILABILITY("Brak wolnego terminu"),
    NO_RESPONSE("Klient przestał odpowiadać"),
    CHOSE_COMPETITOR("Wybrał konkurencję"),
    TOO_FAR("Za daleko od studia"),
    PRICE_CHECK_ONLY("Tylko sprawdzał cenę"),
    VEHICLE_CONDITION("Stan auta wyklucza usługę"),
    SOLD_VEHICLE("Sprzedał albo zmienił auto"),
    OTHER("Inny powód"),

    // ── Nie-straty: nigdy nie były naszymi pieniędzmi ───────────────────────
    DECLINED_BY_US("Sami odmówiliśmy", countsAsLoss = false),
    OUT_OF_SCOPE("Poza zakresem usług", countsAsLoss = false),
    POSTPONED("Odłożył decyzję na później", countsAsLoss = false),
    SPAM("Spam / nie było zapytaniem", countsAsLoss = false)
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
 * Stan rozpoznawania pojazdu z korespondencji.
 *
 * Trzeci stan — „sprawdziliśmy i nie znaleźliśmy" — wynika z danych: DONE bez marki.
 * Osobna wartość dla niego niczego by nie wniosła, a dokładałaby stan do pilnowania.
 */
enum class LeadVehicleDetectionStatus {
    /** Rozpoznanie w toku; interfejs pokazuje spinner. */
    PENDING,
    /** Rozpoznanie zakończone — z marką albo bez niej. */
    DONE
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
    /** Zawsze wartość z katalogu pojazdów albo null — nigdy surowy tekst klienta. */
    val vehicleBrand: String? = null,
    val vehicleModel: String? = null,
    val vehicleDetectionStatus: LeadVehicleDetectionStatus = LeadVehicleDetectionStatus.DONE,
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
