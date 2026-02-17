package pl.detailing.crm.audit.domain

import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant
import java.util.UUID

/**
 * Modules tracked by the audit system.
 * Each module corresponds to a top-level business domain.
 */
enum class AuditModule {
    CUSTOMER,
    VEHICLE,
    VISIT,
    APPOINTMENT,
    SERVICE,
    LEAD,
    PROTOCOL,
    CONSENT,
    INBOUND_CALL,
    APPOINTMENT_COLOR,
    STUDIO,
    USER
}

/**
 * Actions that can be performed on an entity.
 */
enum class AuditAction {
    // CRUD operations
    CREATE,
    UPDATE,
    DELETE,

    // Status transitions
    STATUS_CHANGE,

    // Sub-entity operations
    PHOTO_ADDED,
    PHOTO_DELETED,
    DOCUMENT_ADDED,
    DOCUMENT_DELETED,
    COMMENT_ADDED,
    COMMENT_UPDATED,
    COMMENT_DELETED,
    NOTE_ADDED,
    NOTE_UPDATED,
    NOTE_DELETED,

    // Visit-specific actions
    SERVICE_ADDED,
    SERVICE_UPDATED,
    SERVICE_REMOVED,
    SERVICES_UPDATED,
    VISIT_CONFIRMED,
    VISIT_CANCELLED,
    VISIT_COMPLETED,
    VISIT_REJECTED,
    VISIT_MARKED_READY,
    VISIT_ARCHIVED,

    // Appointment-specific
    APPOINTMENT_CANCELLED,
    APPOINTMENT_CONVERTED,

    // Protocol-specific
    PROTOCOL_GENERATED,
    PROTOCOL_SIGNED,

    // Consent-specific
    CONSENT_GRANTED,
    CONSENT_REVOKED,

    // Lead-specific
    LEAD_CONVERTED,
    LEAD_ABANDONED,

    // Inbound-specific
    CALL_ACCEPTED,
    CALL_REJECTED,

    // Vehicle-specific
    OWNER_ADDED,
    OWNER_REMOVED,
    APPOINTMENT_ADDED,
    VISIT_ADDED,

    // Company data
    COMPANY_UPDATED,
    COMPANY_DELETED
}

/**
 * Represents a single field change within an audit event.
 */
data class FieldChange(
    val field: String,
    val oldValue: String?,
    val newValue: String?
)

/**
 * Type-safe ID wrapper for AuditLog entities.
 */
@JvmInline
value class AuditLogId(val value: UUID) {
    companion object {
        fun random() = AuditLogId(UUID.randomUUID())
        fun fromString(value: String) = AuditLogId(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}

/**
 * Domain model for an audit log entry.
 * Represents a single auditable action performed by a user on an entity.
 */
data class AuditLog(
    val id: AuditLogId,
    val studioId: StudioId,
    val userId: UserId,
    val userDisplayName: String,
    val module: AuditModule,
    val entityId: String,
    val entityDisplayName: String?,
    val action: AuditAction,
    val changes: List<FieldChange>,
    val metadata: Map<String, String>,
    val createdAt: Instant
)
