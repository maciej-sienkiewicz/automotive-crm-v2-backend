package pl.detailing.crm.audit.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "audit_logs",
    indexes = [
        Index(name = "idx_audit_logs_studio_created", columnList = "studio_id, created_at DESC"),
        Index(name = "idx_audit_logs_studio_module", columnList = "studio_id, module, created_at DESC"),
        Index(name = "idx_audit_logs_studio_entity", columnList = "studio_id, module, entity_id, created_at DESC"),
        Index(name = "idx_audit_logs_studio_action", columnList = "studio_id, action, created_at DESC"),
        Index(name = "idx_audit_logs_studio_user", columnList = "studio_id, user_id, created_at DESC")
    ]
)
class AuditLogEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    val userId: UUID,

    @Column(name = "user_display_name", nullable = false, length = 200)
    val userDisplayName: String,

    @Column(name = "module", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    val module: AuditModule,

    @Column(name = "entity_id", nullable = false, length = 100)
    val entityId: String,

    @Column(name = "entity_display_name", length = 500)
    val entityDisplayName: String?,

    @Column(name = "action", nullable = false, length = 50)
    @Enumerated(EnumType.STRING)
    val action: AuditAction,

    @Column(name = "changes", columnDefinition = "jsonb")
    val changes: String?,

    @Column(name = "metadata", columnDefinition = "jsonb")
    val metadata: String?,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant = Instant.now()
) {
    fun toDomain(changesParser: (String?) -> List<FieldChange>, metadataParser: (String?) -> Map<String, String>): AuditLog =
        AuditLog(
            id = AuditLogId(id),
            studioId = StudioId(studioId),
            userId = UserId(userId),
            userDisplayName = userDisplayName,
            module = module,
            entityId = entityId,
            entityDisplayName = entityDisplayName,
            action = action,
            changes = changesParser(changes),
            metadata = metadataParser(metadata),
            createdAt = createdAt
        )

    companion object {
        fun fromDomain(
            auditLog: AuditLog,
            changesSerializer: (List<FieldChange>) -> String?,
            metadataSerializer: (Map<String, String>) -> String?
        ): AuditLogEntity = AuditLogEntity(
            id = auditLog.id.value,
            studioId = auditLog.studioId.value,
            userId = auditLog.userId.value,
            userDisplayName = auditLog.userDisplayName,
            module = auditLog.module,
            entityId = auditLog.entityId,
            entityDisplayName = auditLog.entityDisplayName,
            action = auditLog.action,
            changes = changesSerializer(auditLog.changes),
            metadata = metadataSerializer(auditLog.metadata),
            createdAt = auditLog.createdAt
        )
    }
}
