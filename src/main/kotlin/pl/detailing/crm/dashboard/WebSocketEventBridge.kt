package pl.detailing.crm.dashboard

import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
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

    @EventListener
    fun handleNewCallReceived(event: NewCallReceivedEvent) {
        log.debug("[WS-BRIDGE] Received NewCallReceivedEvent: callId={}, studioId={}, phone={}",
            event.callId.value, event.studioId.value, event.phoneNumber)

        val payload = NewCallPayload(
            id = event.callId.value.toString(),
            phoneNumber = event.phoneNumber,
            callerName = event.callerName,
            receivedAt = event.receivedAt
        )

        val dashboardEvent = DashboardEvent(
            type = DashboardEventType.NEW_INBOUND_CALL,
            payload = payload,
            timestamp = event.receivedAt
        )

        val destination = "/topic/studio.${event.studioId.value}.dashboard"
        log.info("[WS-BRIDGE] Sending DashboardEvent to destination={}, type={}", destination, dashboardEvent.type)
        messagingTemplate.convertAndSend(destination, dashboardEvent)
        log.debug("[WS-BRIDGE] Message sent successfully to {}", destination)
    }
}
