package pl.detailing.crm.shared

import org.springframework.context.ApplicationEvent
import java.time.Instant

/**
 * Types of real-time dashboard events sent via WebSocket
 */
enum class DashboardEventType {
    NEW_INBOUND_CALL
}

/**
 * WebSocket payload wrapper for dashboard events.
 * Sent to /topic/studio.{studioId}.dashboard
 */
data class DashboardEvent<T>(
    val type: DashboardEventType,
    val payload: T,
    val timestamp: Instant = Instant.now()
)

/**
 * Payload for NEW_INBOUND_CALL event
 */
data class NewCallPayload(
    val id: String,
    val phoneNumber: String,
    val callerName: String?,
    val receivedAt: Instant
)

/**
 * Internal Spring ApplicationEvent published when a new inbound call is registered.
 * Used to decouple the inbound slice from the dashboard/WebSocket slice.
 */
class NewCallReceivedEvent(
    source: Any,
    val studioId: StudioId,
    val callId: CallId,
    val phoneNumber: String,
    val callerName: String?,
    val receivedAt: Instant
) : ApplicationEvent(source)
