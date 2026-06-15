package pl.detailing.crm.dashboard

import org.slf4j.LoggerFactory
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import org.springframework.transaction.event.TransactionPhase
import pl.detailing.crm.shared.*

/**
 * Bridges internal Spring ApplicationEvents to WebSocket STOMP messages.
 * Listens for domain events and pushes real-time updates to the correct
 * tenant-specific topic: /topic/studio.{studioId}.dashboard
 */
@Component
class WebSocketEventBridge(
    private val messagingTemplate: SimpMessagingTemplate
) {
    private val log = LoggerFactory.getLogger(WebSocketEventBridge::class.java)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun handleNewCallReceived(event: NewCallReceivedEvent) {
        log.debug("[WS-BRIDGE] Received NewCallReceivedEvent: callId={}, leadId={}, studioId={}, phone={}",
            event.callId.value, event.leadId.value, event.studioId.value, event.phoneNumber)

        val payload = NewCallPayload(
            id = event.leadId.value.toString(),
            phoneNumber = event.phoneNumber,
            callerName = event.callerName,
            receivedAt = event.receivedAt,
            estimatedValue = event.estimatedValue
        )

        val dashboardEvent = DashboardEvent(
            type = DashboardEventType.NEW_INBOUND_CALL,
            payload = payload,
            timestamp = event.receivedAt
        )

        val destination = "/topic/studio.${event.studioId.value}.dashboard"
        log.info("[WS-BRIDGE] Sending DashboardEvent to destination={}, type={}, leadId={}", 
            destination, dashboardEvent.type, event.leadId.value)
        messagingTemplate.convertAndSend(destination, dashboardEvent)
        log.debug("[WS-BRIDGE] Message sent successfully to {}", destination)
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun handleNewLeadCreated(event: NewLeadCreatedEvent) {
        log.debug("[WS-BRIDGE] Received NewLeadCreatedEvent: leadId={}, studioId={}, source={}",
            event.leadId.value, event.studioId.value, event.leadSource)

        val payload = NewLeadPayload(
            id = event.leadId.value.toString(),
            source = event.leadSource.name,
            contactIdentifier = event.contactIdentifier,
            customerName = event.customerName,
            estimatedValue = event.estimatedValue,
            createdAt = event.createdAt
        )

        val dashboardEvent = DashboardEvent(
            type = DashboardEventType.NEW_LEAD,
            payload = payload,
            timestamp = event.createdAt
        )

        val destination = "/topic/studio.${event.studioId.value}.dashboard"
        log.info("[WS-BRIDGE] Sending DashboardEvent to destination={}, type={}, leadId={}",
            destination, dashboardEvent.type, event.leadId.value)
        messagingTemplate.convertAndSend(destination, dashboardEvent)
        log.debug("[WS-BRIDGE] Message sent successfully to {}", destination)
    }
}
