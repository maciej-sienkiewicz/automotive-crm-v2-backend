package pl.detailing.crm.comms

import org.slf4j.LoggerFactory
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import pl.detailing.crm.comms.domain.CommMessageReadEvent
import pl.detailing.crm.comms.domain.CommThreadChangedEvent
import pl.detailing.crm.shared.CommMessageReadPayload
import pl.detailing.crm.shared.CommThreadUpdatedPayload
import pl.detailing.crm.shared.DashboardEvent
import pl.detailing.crm.shared.DashboardEventType
import java.util.UUID

/**
 * Pushes comms events to the studio's dashboard topic. Payloads are id-only — the
 * frontend refetches over REST — so nothing personal rides on the broadcast and the
 * push can stay best-effort (a missed event only delays the UI until the next fetch).
 *
 * AFTER_COMMIT with fallbackExecution matches WebSocketEventBridge: sync engine code
 * publishes from scheduler/IDLE threads that are not always transaction-bound.
 */
@Component
class CommsWebSocketBridge(
    private val messagingTemplate: SimpMessagingTemplate
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onThreadChanged(event: CommThreadChangedEvent) {
        send(
            event.studioId,
            DashboardEvent(
                type = DashboardEventType.COMM_THREAD_UPDATED,
                payload = CommThreadUpdatedPayload(
                    threadId = event.threadId.toString(),
                    newMessage = event.newMessage
                )
            )
        )
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onMessageRead(event: CommMessageReadEvent) {
        send(
            event.studioId,
            DashboardEvent(
                type = DashboardEventType.COMM_MESSAGE_READ,
                payload = CommMessageReadPayload(
                    threadId = event.threadId.toString(),
                    messageId = event.messageId.toString(),
                    readSource = event.readSource.name
                )
            )
        )
    }

    private fun send(studioId: UUID, event: DashboardEvent<*>) {
        val destination = "/topic/studio.$studioId.dashboard"
        try {
            messagingTemplate.convertAndSend(destination, event)
        } catch (e: Exception) {
            log.error("[COMMS-WS] Failed to send {} to {}: {}", event.type, destination, e.message)
        }
    }
}
