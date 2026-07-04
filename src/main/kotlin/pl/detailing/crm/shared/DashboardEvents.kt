package pl.detailing.crm.shared

import org.springframework.context.ApplicationEvent
import java.time.Instant

/**
 * Types of real-time dashboard events sent via WebSocket.
 * These names are part of the frontend contract — the frontend ignores unknown
 * types, so renaming a value here silently breaks notifications in the browser.
 */
enum class DashboardEventType {
    NEW_LEAD,
    LEAD_UPDATED,
    LEAD_STATUS_CHANGED,
    LEAD_CLIENT_REPLIED
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
 * Payload for NEW_LEAD event — canonical shape expected by the frontend
 * (contactIdentifier / customerName / createdAt), used for every lead source
 * including inbound phone calls.
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
 * Payload for LEAD_CLIENT_REPLIED event — a follow-up email was appended as a comment.
 * [customerName] is the assigned customer name if known, otherwise the raw contactIdentifier (e-mail).
 */
data class LeadClientRepliedPayload(
    val leadId: String,
    val customerName: String,
    val activityAt: Instant
)

/**
 * Internal Spring ApplicationEvent published when a client's follow-up email is appended
 * as a comment to an existing open lead (dedup path).
 */
class LeadClientRepliedEvent(
    source: Any,
    val studioId: StudioId,
    val leadId: LeadId,
    val customerName: String,
    val activityAt: Instant
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

/**
 * Internal Spring ApplicationEvent published whenever an existing lead is mutated
 * (status, assignment, customer, links, estimation results, …). The WebSocket bridge
 * loads the full lead DTO after commit and broadcasts LEAD_UPDATED or
 * LEAD_STATUS_CHANGED to the studio's dashboard topic, so every logged-in user of the
 * studio sees the change without refreshing.
 */
class LeadChangedEvent(
    source: Any,
    val studioId: StudioId,
    val leadId: LeadId,
    val statusChanged: Boolean = false
) : ApplicationEvent(source)
