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
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitCompletedEvent
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/**
 * Turns domain events into Web Push notifications, mirroring how
 * [pl.detailing.crm.dashboard.WebSocketEventBridge] turns them into dashboard
 * messages: AFTER_COMMIT, asynchronous, best-effort.
 *
 * AFTER_COMMIT is the load-bearing part. A notification is not a database row —
 * it cannot be rolled back, deleted or corrected once it has buzzed in someone's
 * pocket. Announcing money for a transaction that later fails would be worse
 * than announcing nothing, so nothing is sent until the transaction is durable.
 */
@Component
class PushEventBridge(
    private val pushNotifier: PushNotifier
) {
    private val log = LoggerFactory.getLogger(PushEventBridge::class.java)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onVisitCompleted(event: VisitCompletedEvent) {
        // A free visit (courtesy job, warranty rework, goodwill) is still a closed
        // visit, but "you just earned 0,00 zł" is noise dressed up as news.
        if (event.totalGrossInCents <= 0) return

        runCatching {
            pushNotifier.broadcast(
                studioId = event.studioId,
                requiredPermission = Permission.FINANCE_EARNINGS_NOTIFICATIONS,
                payload = PushPayload(
                    type = PushNotificationType.VISIT_COMPLETED,
                    // The amount carries the message, so it goes in the title — the one
                    // line every phone shows in full, in bold, on the lock screen. No
                    // exclamation mark and no emoji: the number is the emphasis.
                    title = "Właśnie zarobiłeś ${formatMoney(event.totalGrossInCents)}",
                    body = listOfNotNull("Wizyta zakończona", event.customerName).joinToString(" · "),
                    url = "/visits/${event.visitId.value}",
                    icon = PushIcon.EARNINGS,
                    // Per visit, not per studio: two cars handed over minutes apart are
                    // two earnings, and collapsing them would hide one.
                    tag = "visit-completed-${event.visitId.value}"
                )
            )
        }.onFailure { log.warn("[push] Nie udalo sie wyslac powiadomienia o zarobku: {}", it.message) }
    }

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

    private fun notifyNewLead(
        studioId: StudioId,
        leadId: String,
        name: String?,
        contact: String?,
        source: LeadSource
    ) {
        runCatching {
            pushNotifier.broadcast(
                studioId = studioId,
                requiredPermission = Permission.LEADS_MANAGE,
                payload = PushPayload(
                    type = PushNotificationType.NEW_LEAD,
                    title = name?.takeIf { it.isNotBlank() }?.let { "Nowy lead: $it" } ?: "Nowy lead",
                    body = listOfNotNull(sourceLabel(source), contact?.takeIf { it.isNotBlank() })
                        .joinToString(" · "),
                    url = "/leads",
                    icon = PushIcon.LEAD,
                    // Per lead, so a second enquiry never silently replaces the first.
                    tag = "lead-$leadId"
                )
            )
        }.onFailure { log.warn("[push] Nie udalo sie wyslac powiadomienia o leadzie: {}", it.message) }
    }

    private fun sourceLabel(source: LeadSource): String = when (source) {
        LeadSource.PHONE -> "Telefon"
        LeadSource.EMAIL -> "E-mail"
        LeadSource.FORM -> "Formularz na stronie"
        LeadSource.MANUAL -> "Dodany ręcznie"
    }

    /**
     * Polish currency formatting: "1 234,50 zł" — non-breaking spaces and a comma,
     * straight from the JDK's pl-PL locale rather than hand-rolled string surgery.
     */
    private fun formatMoney(amountInCents: Long): String =
        NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pl-PL"))
            .format(BigDecimal(amountInCents).divide(BigDecimal(100), 2, RoundingMode.HALF_UP))
}
