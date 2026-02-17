package pl.detailing.crm.audit.domain

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.infrastructure.AuditLogEntity
import pl.detailing.crm.audit.infrastructure.AuditLogRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant

/**
 * Command object for logging an audit event.
 * This is the primary API for all modules to record audit entries.
 */
data class LogAuditCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userDisplayName: String,
    val module: AuditModule,
    val entityId: String,
    val entityDisplayName: String? = null,
    val action: AuditAction,
    val changes: List<FieldChange> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Core audit service providing generic audit logging for all modules.
 *
 * Usage from handlers:
 * ```
 * auditService.log(LogAuditCommand(
 *     studioId = command.studioId,
 *     userId = command.userId,
 *     userDisplayName = "Jan Kowalski",
 *     module = AuditModule.CUSTOMER,
 *     entityId = customerId.toString(),
 *     entityDisplayName = "Jan Kowalski",
 *     action = AuditAction.UPDATE,
 *     changes = listOf(FieldChange("email", "old@mail.com", "new@mail.com"))
 * ))
 * ```
 */
@Service
class AuditService(
    private val auditLogRepository: AuditLogRepository,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(AuditService::class.java)

    /**
     * Log an audit event. This method is designed to never throw exceptions
     * to avoid breaking the primary business flow.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    suspend fun log(command: LogAuditCommand) {
        try {
            withContext(Dispatchers.IO) {
                val auditLog = AuditLog(
                    id = AuditLogId.random(),
                    studioId = command.studioId,
                    userId = command.userId,
                    userDisplayName = command.userDisplayName,
                    module = command.module,
                    entityId = command.entityId,
                    entityDisplayName = command.entityDisplayName,
                    action = command.action,
                    changes = command.changes,
                    metadata = command.metadata,
                    createdAt = Instant.now()
                )

                val entity = AuditLogEntity.fromDomain(
                    auditLog = auditLog,
                    changesSerializer = ::serializeChanges,
                    metadataSerializer = ::serializeMetadata
                )

                auditLogRepository.save(entity)
            }
        } catch (e: Exception) {
            logger.error("Failed to save audit log: module=${command.module}, action=${command.action}, entityId=${command.entityId}", e)
        }
    }

    /**
     * Log an audit event synchronously (for use in non-coroutine contexts).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun logSync(command: LogAuditCommand) {
        try {
            val auditLog = AuditLog(
                id = AuditLogId.random(),
                studioId = command.studioId,
                userId = command.userId,
                userDisplayName = command.userDisplayName,
                module = command.module,
                entityId = command.entityId,
                entityDisplayName = command.entityDisplayName,
                action = command.action,
                changes = command.changes,
                metadata = command.metadata,
                createdAt = Instant.now()
            )

            val entity = AuditLogEntity.fromDomain(
                auditLog = auditLog,
                changesSerializer = ::serializeChanges,
                metadataSerializer = ::serializeMetadata
            )

            auditLogRepository.save(entity)
        } catch (e: Exception) {
            logger.error("Failed to save audit log (sync): module=${command.module}, action=${command.action}, entityId=${command.entityId}", e)
        }
    }

    /**
     * Helper to compute field-level changes between old and new maps of values.
     * Keys represent field names, values represent the field values as strings.
     *
     * Only changed fields are returned.
     */
    fun computeChanges(oldValues: Map<String, String?>, newValues: Map<String, String?>): List<FieldChange> {
        val changes = mutableListOf<FieldChange>()
        val allKeys = oldValues.keys + newValues.keys

        for (key in allKeys) {
            val oldVal = oldValues[key]
            val newVal = newValues[key]
            if (oldVal != newVal) {
                changes.add(FieldChange(field = key, oldValue = oldVal, newValue = newVal))
            }
        }

        return changes
    }

    // JSON serialization helpers

    fun serializeChanges(changes: List<FieldChange>): String? {
        if (changes.isEmpty()) return null
        return objectMapper.writeValueAsString(changes)
    }

    fun deserializeChanges(json: String?): List<FieldChange> {
        if (json.isNullOrBlank()) return emptyList()
        return objectMapper.readValue(json)
    }

    fun serializeMetadata(metadata: Map<String, String>): String? {
        if (metadata.isEmpty()) return null
        return objectMapper.writeValueAsString(metadata)
    }

    fun deserializeMetadata(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return objectMapper.readValue(json)
    }
}
