package pl.detailing.crm.dashboard

import org.slf4j.LoggerFactory
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import pl.detailing.crm.leads.snapshot.LeadSnapshotService
import pl.detailing.crm.shared.*
import pl.detailing.crm.shared.pii.PiiAccessContext

/**
 * Bridges internal Spring ApplicationEvents to WebSocket STOMP messages.
 * Listens for domain events and pushes real-time updates to the correct
 * tenant-specific topic: /topic/studio.{studioId}.dashboard
 *
 * All listeners run AFTER_COMMIT: the frontend reacts to an event by refetching
 * REST endpoints, so an event emitted before the transaction commits would make
 * the refetch miss the data (toast about a lead that "does not exist").
 * fallbackExecution=true keeps the bridge working when an event is published
 * outside of an active thread-bound transaction (e.g. from coroutine dispatchers).
 */
@Component
class WebSocketEventBridge(
    private val messagingTemplate: SimpMessagingTemplate,
    private val leadSnapshotService: LeadSnapshotService
) {
    private val log = LoggerFactory.getLogger(WebSocketEventBridge::class.java)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun handleNewCallReceived(event: NewCallReceivedEvent) {
        // Inbound phone calls also create a lead — the frontend only understands the
        // canonical NEW_LEAD type (unknown types are ignored), so a dedicated
        // "new inbound call" type would silently produce no toast for phone leads.
        val payload = NewLeadPayload(
            id = event.leadId.value.toString(),
            source = LeadSource.PHONE.name,
            contactIdentifier = event.phoneNumber,
            customerName = event.callerName,
            estimatedValue = event.estimatedValue,
            createdAt = event.receivedAt
        )
        send(event.studioId, DashboardEvent(
            type = DashboardEventType.NEW_LEAD,
            payload = payload,
            timestamp = event.receivedAt
        ), "leadId=${event.leadId.value} callId=${event.callId.value}")
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun handleNewLeadCreated(event: NewLeadCreatedEvent) {
        val payload = NewLeadPayload(
            id = event.leadId.value.toString(),
            source = event.leadSource.name,
            contactIdentifier = event.contactIdentifier,
            customerName = event.customerName,
            estimatedValue = event.estimatedValue,
            createdAt = event.createdAt
        )
        send(event.studioId, DashboardEvent(
            type = DashboardEventType.NEW_LEAD,
            payload = payload,
            timestamp = event.createdAt
        ), "leadId=${event.leadId.value}")
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun handleLeadClientReplied(event: LeadClientRepliedEvent) {
        val payload = LeadClientRepliedPayload(
            leadId = event.leadId.value.toString(),
            customerName = event.customerName,
            activityAt = event.activityAt
        )
        send(event.studioId, DashboardEvent(
            type = DashboardEventType.LEAD_CLIENT_REPLIED,
            payload = payload,
            timestamp = event.activityAt
        ), "leadId=${event.leadId.value}")
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun handleLeadChanged(event: LeadChangedEvent) {
        // Load the full lead DTO after commit — the frontend replaces the entire row
        // in its cache with this payload, so it must match GET /api/v1/leads exactly.
        val dto = leadSnapshotService.getLeadDto(event.studioId, event.leadId)
        if (dto == null) {
            log.debug("[WS-BRIDGE] Skipping LeadChangedEvent for missing lead {}", event.leadId.value)
            return
        }
        val type = if (event.statusChanged) {
            DashboardEventType.LEAD_STATUS_CHANGED
        } else {
            DashboardEventType.LEAD_UPDATED
        }
        send(event.studioId, DashboardEvent(type = type, payload = dto), "leadId=${event.leadId.value}")
    }

    private fun send(studioId: StudioId, event: DashboardEvent<*>, context: String) {
        val destination = "/topic/studio.${studioId.value}.dashboard"
        try {
            // The dashboard topic is studio-wide: subscribers' permissions are unknown at
            // send time, so personal data must never ride on a broadcast. withMasked makes
            // that explicit instead of relying on this listener staying @Async (off-request
            // threads are masked by default).
            PiiAccessContext.withMasked {
                messagingTemplate.convertAndSend(destination, event)
            }
            log.info("[WS-BRIDGE] Sent {} to {} ({})", event.type, destination, context)
        } catch (e: Exception) {
            // Delivery is best-effort (at-most-once): the frontend refetches REST after
            // every reconnect, so a failed push must never break the business flow —
            // but it has to be visible in the logs.
            log.error("[WS-BRIDGE] Failed to send {} to {} ({}): {}",
                event.type, destination, context, e.message, e)
        }
    }
}
