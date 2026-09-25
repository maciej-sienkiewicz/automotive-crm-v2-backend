package pl.detailing.crm.push.notify

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.shared.LeadSource
import pl.detailing.crm.shared.NewCallReceivedEvent
import pl.detailing.crm.shared.NewLeadCreatedEvent
import pl.detailing.crm.shared.ReservationCreatedEvent
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VehicleCheckedInEvent
import pl.detailing.crm.shared.VisitCompletedEvent

/**
 * Turns domain events into Web Push notifications, mirroring how
 * [pl.detailing.crm.dashboard.WebSocketEventBridge] turns them into dashboard
 * messages: AFTER_COMMIT, asynchronous, best-effort.
 *
 * AFTER_COMMIT is the load-bearing part. A notification is not a database row —
 * it cannot be rolled back, deleted or corrected once it has buzzed in someone's
 * pocket. Announcing money for a transaction that later fails would be worse
 * than announcing nothing, so nothing is sent until the transaction is durable.
 *
 * The wording lives in [PushMessages]; who receives what (permission, personal
 * data, the author left out) in [PushNotifier]. This class only connects the two.
 *
 * The fifth notification — a competitor's campaign in the tracked area — does not
 * start from a user action and lives next to its data:
 * [pl.detailing.crm.instagram.ads.discovery.AreaCampaignNotifier].
 */
@Component
class PushEventBridge(
    private val pushNotifier: PushNotifier
) {
    private val log = LoggerFactory.getLogger(PushEventBridge::class.java)

    /** d) Pojazd wydany klientowi - studio zarobiło. */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onVisitCompleted(event: VisitCompletedEvent) {
        // A free visit (courtesy job, warranty rework, goodwill) is still a closed
        // visit, but "you just earned 0,00 zł" is noise dressed up as news.
        if (event.totalGrossInCents <= 0) return

        send("zarobku") {
            pushNotifier.broadcast(
                studioId = event.studioId,
                requiredPermission = Permission.FINANCE_EARNINGS_NOTIFICATIONS,
                message = PushMessages.visitCompleted(
                    visitId = event.visitId.value.toString(),
                    totalGrossInCents = event.totalGrossInCents,
                    vehicle = event.vehicleLabel,
                    customerName = event.customerName
                )
                // The author is NOT left out here: money coming in is news to the owner
                // even when the owner handed the car over personally.
            )
        }
    }

    /** a) Nowy lead - z formularza, poczty albo dodany ręcznie. */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onNewLeadCreated(event: NewLeadCreatedEvent) {
        notifyNewLead(
            studioId = event.studioId,
            leadId = event.leadId.value.toString(),
            name = event.customerName,
            contact = event.contactIdentifier,
            source = event.leadSource
        )
    }

    /**
     * Inbound calls create a lead too, but travel on their own event — without this
     * listener the notification would cover every lead source except the one that
     * rings while nobody is at the desk.
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onNewCallReceived(event: NewCallReceivedEvent) {
        notifyNewLead(
            studioId = event.studioId,
            leadId = event.leadId.value.toString(),
            name = event.callerName,
            contact = event.phoneNumber,
            source = LeadSource.PHONE
        )
    }

    /** b) Nowa rezerwacja w kalendarzu. */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onReservationCreated(event: ReservationCreatedEvent) {
        send("rezerwacji") {
            pushNotifier.broadcast(
                studioId = event.studioId,
                requiredPermission = Permission.VISITS_VIEW,
                message = PushMessages.reservationCreated(
                    appointmentId = event.appointmentId.value.toString(),
                    start = event.startDateTime,
                    allDay = event.allDay,
                    vehicle = event.vehicleLabel,
                    serviceNames = event.serviceNames,
                    customerName = event.customerName
                ),
                // Whoever booked the slot knows about it; the rest of the team does not.
                excludeUserId = event.createdByUserId
            )
        }
    }

    /** c) Przyjęcie pojazdu - wizyta potwierdzona po podpisaniu protokołu. */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onVehicleCheckedIn(event: VehicleCheckedInEvent) {
        send("przyjeciu pojazdu") {
            pushNotifier.broadcast(
                studioId = event.studioId,
                requiredPermission = Permission.VISITS_VIEW,
                message = PushMessages.vehicleCheckedIn(
                    visitId = event.visitId.value.toString(),
                    visitNumber = event.visitNumber,
                    brandModel = event.brandModel,
                    licensePlate = event.licensePlate,
                    customerName = event.customerName
                ),
                excludeUserId = event.checkedInByUserId
            )
        }
    }

    private fun notifyNewLead(
        studioId: StudioId,
        leadId: String,
        name: String?,
        contact: String?,
        source: LeadSource
    ) {
        send("leadzie") {
            pushNotifier.broadcast(
                studioId = studioId,
                requiredPermission = Permission.LEADS_MANAGE,
                message = PushMessages.newLead(leadId, name, contact, source)
            )
        }
    }

    private inline fun send(what: String, block: () -> Unit) {
        runCatching(block).onFailure { log.warn("[push] Nie udalo sie wyslac powiadomienia o {}: {}", what, it.message) }
    }
}
