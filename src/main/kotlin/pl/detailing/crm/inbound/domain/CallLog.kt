package pl.detailing.crm.inbound.domain

import pl.detailing.crm.shared.CallId
import pl.detailing.crm.shared.CallLogStatus
import pl.detailing.crm.shared.StudioId
import java.time.Instant

/**
 * Domain model for inbound call tracking
 * Represents calls received from iOS Shortcuts or other sources
 */
data class CallLog(
    val id: CallId,
    val studioId: StudioId,
    val phoneNumber: String,
    val callerName: String?,
    val note: String?,
    val status: CallLogStatus,
    val receivedAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant
)
