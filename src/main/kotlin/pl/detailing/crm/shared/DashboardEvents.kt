package pl.detailing.crm.shared

import org.springframework.context.ApplicationEvent
import java.time.Instant

/**
 * Types of real-time dashboard events sent via WebSocket
 */
enum class DashboardEventType {
    NEW_INBOUND_CALL,
    NEW_LEAD
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
    val receivedAt: Instant,
    val estimatedValue: Long
)

/**
 * Payload for NEW_LEAD event (email, voice and other non-call sources)
 */
data class NewLeadPayload(
    val id: String,
    val source: String,
    val contactIdentifier: String,
    val customerName: String?,
    val estimatedValue: Long,
    val createdAt: Instant
)

/**
 * Internal Spring ApplicationEvent published when a new inbound call is registered.
 * Used to decouple the inbound slice from the dashboard/WebSocket slice.
 */
class NewCallReceivedEvent(
    source: Any,
    val studioId: StudioId,
    val callId: CallId,
    val leadId: LeadId,
    val phoneNumber: String,
    val callerName: String?,
    val receivedAt: Instant,
    val estimatedValue: Long
) : ApplicationEvent(source)

/**
 * Internal Spring ApplicationEvent published when a lead is created from any non-call source.
 */
class NewLeadCreatedEvent(
    source: Any,
    val studioId: StudioId,
    val leadId: LeadId,
    val leadSource: LeadSource,
    val contactIdentifier: String,
    val customerName: String?,
    val estimatedValue: Long,
    val createdAt: Instant
) : ApplicationEvent(source)

