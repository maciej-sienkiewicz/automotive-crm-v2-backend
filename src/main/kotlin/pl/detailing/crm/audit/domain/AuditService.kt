package pl.detailing.crm.audit.domain

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.audit.infrastructure.AuditLogEntity
import pl.detailing.crm.audit.infrastructure.AuditLogWriter
import pl.detailing.crm.audit.infrastructure.AuditRequestContext
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant
import java.util.UUID

/**
 * An auditable thing that happened. This is the API to reach for in new code.
 *
 * Everything except the actor, the module, the action and the entity is optional, and the
 * optional parts are what make the entry render well in the activity feed:
 * [context] gives the feed something to link to, [amount] answers "for how much", and
 * [severity] / [channel] are derived automatically unless the call site knows better.
 *
 * ```
 * auditService.record(AuditEvent(
 *     studioId = command.studioId,
 *     actor = AuditActor.employee(command.userId, command.userName),
 *     module = AuditModule.VISIT,
 *     action = AuditAction.VISIT_CREATED,
 *     entityId = visitId.toString(),
 *     entityDisplayName = "Wizyta #${visit.visitNumber}",
 *     context = AuditContext(customerId = customerId, vehicleId = vehicleId),
 *     amount = AuditAmount(visit.totalGross)
 * ))
 * ```
 */
data class AuditEvent(
    val studioId: StudioId,
    val actor: AuditActor,
    val module: AuditModule,
    val action: AuditAction,
    val entityId: String,
    val entityDisplayName: String? = null,
    val changes: List<FieldChange> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val context: AuditContext = AuditContext.EMPTY,
    val amount: AuditAmount? = null,
    /** Defaults to the action's declared severity. */
    val severity: AuditSeverity? = null,
    /** Defaults to the ambient HTTP request, then to the actor type. */
    val channel: AuditChannel? = null,
    val correlationId: UUID? = null
)

/**
 * Command object for logging an audit event.
 *
 * Retained as the compatibility surface for the ~90 handlers written against it. New code
 * should use [AuditEvent], which can express a customer or system actor; this type is
 * pinned to an employee [userId] and cannot.
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
    val metadata: Map<String, String> = emptyMap(),
    // v2 additions — optional, so existing call sites are unaffected.
    val context: AuditContext = AuditContext.EMPTY,
    val amount: AuditAmount? = null,
    val severity: AuditSeverity? = null,
    val channel: AuditChannel? = null,
    val correlationId: UUID? = null
) {
    fun toEvent(): AuditEvent = AuditEvent(
        studioId = studioId,
        actor = AuditActor.employee(userId, userDisplayName),
        module = module,
        action = action,
        entityId = entityId,
        entityDisplayName = entityDisplayName,
        changes = changes,
        metadata = metadata,
        context = context,
        amount = amount,
        severity = severity,
        channel = channel,
        correlationId = correlationId
    )
}

/**
 * Core audit service providing generic audit logging for all modules.
 *
 * Logging never throws: a failure to record an event must not take down the business
 * operation that caused it. It is reported at ERROR together with the full event payload,
 * so a dropped entry stays reconstructable from the application log.
 */
@Service
class AuditService(
    private val auditLogWriter: AuditLogWriter,
    private val auditLogRepository: pl.detailing.crm.audit.infrastructure.AuditLogRepository,
    private val auditMetadataEnricher: AuditMetadataEnricher,
    private val objectMapper: ObjectMapper,
    /** Moduły właścicielskie dokładają swoje — pusta lista jest poprawną konfiguracją. */
    private val contextResolvers: List<AuditContextResolver> = emptyList()
) {
    private val logger = LoggerFactory.getLogger(AuditService::class.java)

    /**
     * Record an event. Safe to call from any coroutine; the database write happens on the
     * IO dispatcher in its own transaction.
     */
    suspend fun record(event: AuditEvent) {
        val entity = try {
            // Captured here, on the caller's thread: the request thread locals are gone by
            // the time the body below runs on the IO dispatcher.
            toEntity(event)
        } catch (e: Exception) {
            reportFailure(event, e)
            return
        }

        try {
            withContext(Dispatchers.IO) { auditLogWriter.write(entity) }
        } catch (e: Exception) {
            reportFailure(event, e)
        }
    }

    /** Record an event from a non-coroutine context (schedulers, listeners, filters). */
    fun recordSync(event: AuditEvent) {
        try {
            auditLogWriter.write(toEntity(event))
        } catch (e: Exception) {
            reportFailure(event, e)
        }
    }

    /** Compatibility wrapper — see [LogAuditCommand]. */
    suspend fun log(command: LogAuditCommand) = record(command.toEvent())

    /** Compatibility wrapper — see [LogAuditCommand]. */
    fun logSync(command: LogAuditCommand) = recordSync(command.toEvent())

    /**
     * Merges extra metadata keys into the newest stored entry of ([action], visit) —
     * used by multi-step flows (check-in → protocol signature) to keep ONE activity
     * entry per business action instead of a burst of near-duplicates.
     *
     * Like [record], never throws. @return true when an entry was found and enriched;
     * false lets the caller fall back to logging its own entry.
     *
     * Deliberately NOT @Transactional, and the write goes through [AuditMetadataEnricher]
     * (REQUIRES_NEW). Enrichment is a cosmetic touch-up of the activity feed and must
     * never be able to fail the business operation that triggered it. When this method
     * shared the caller's transaction, a failing UPDATE marked that transaction
     * rollback-only; the catch below swallowed the exception, but the commit then threw
     * UnexpectedRollbackException from the proxy — OUTSIDE this try — and a fully
     * completed signature flow (PDF composed, sealed and uploaded to S3) still returned
     * HTTP 500 to the tablet. Catching outside the transaction boundary is what makes
     * the "never throws" contract above actually hold.
     */
    fun enrichLatestEntry(
        studioId: StudioId,
        action: AuditAction,
        visitId: java.util.UUID,
        patch: Map<String, String>
    ): Boolean = try {
        val id = auditLogRepository.findLatestIdByStudioIdAndActionAndVisitId(
            studioId.value, action.name, visitId
        )
        id != null && auditMetadataEnricher.merge(id, objectMapper.writeValueAsString(patch))
    } catch (e: Exception) {
        logger.error(
            "Failed to enrich audit entry: studioId={}, action={}, visitId={}, patch={}",
            studioId, action, visitId, patch, e
        )
        false
    }

    /**
     * Kontekst z pierwszej ręki wygrywa; brakujący dokleja resolver modułu.
     * W runCatching — dopisanie kontekstu jest kosmetyką feedu i nie ma prawa
     * wywrócić zapisu zdarzenia ani operacji biznesowej nad nim.
     */
    private fun resolveContext(event: AuditEvent): AuditContext {
        if (!event.context.isEmpty) return event.context
        return contextResolvers.firstNotNullOfOrNull { resolver ->
            runCatching { resolver.resolve(event.studioId, event.module, event.entityId) }
                .onFailure {
                    logger.warn(
                        "Audit context resolution failed: module={}, entityId={}: {}",
                        event.module, event.entityId, it.message
                    )
                }
                .getOrNull()
        } ?: AuditContext.EMPTY
    }

    private fun toEntity(event: AuditEvent): AuditLogEntity {
        val request = AuditRequestContext.capture()

        val auditLog = AuditLog(
            id = AuditLogId.random(),
            studioId = event.studioId,
            actor = event.actor,
            module = event.module,
            entityId = event.entityId,
            entityDisplayName = event.entityDisplayName,
            action = event.action,
            changes = event.changes,
            metadata = event.metadata,
            context = resolveContext(event),
            amount = event.amount,
            severity = event.severity ?: event.action.severity,
            channel = event.channel ?: request?.channel ?: defaultChannelFor(event.actor.type),
            ipAddress = request?.ipAddress,
            correlationId = event.correlationId ?: request?.correlationId,
            createdAt = Instant.now()
        )

        return AuditLogEntity.fromDomain(
            auditLog = auditLog,
            changesSerializer = ::serializeChanges,
            metadataSerializer = ::serializeMetadata
        )
    }

    /**
     * Used when no HTTP request is visible. That is not proof the action was automated —
     * a handler that already switched dispatchers looks the same — so an employee action
     * is attributed to the panel rather than being mislabelled as a system job.
     */
    private fun defaultChannelFor(actorType: AuditActorType): AuditChannel = when (actorType) {
        AuditActorType.EMPLOYEE -> AuditChannel.WEB_PANEL
        AuditActorType.CUSTOMER -> AuditChannel.PUBLIC_LINK
        AuditActorType.SYSTEM -> AuditChannel.SYSTEM
        AuditActorType.INTEGRATION -> AuditChannel.API
    }

    private fun reportFailure(event: AuditEvent, e: Exception) {
        logger.error(
            "Failed to save audit log: studioId={}, module={}, action={}, entityId={}, actor={}/{}, payload={}",
            event.studioId,
            event.module,
            event.action,
            event.entityId,
            event.actor.type,
            event.actor.id,
            runCatching { objectMapper.writeValueAsString(event) }.getOrElse { "<unserializable>" },
            e
        )
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
        return try {
            objectMapper.readValue(json)
        } catch (e: Exception) {
            // A malformed payload must not take down the whole page of results.
            logger.warn("Unreadable audit changes payload, rendering entry without changes", e)
            emptyList()
        }
    }

    fun serializeMetadata(metadata: Map<String, String>): String? {
        if (metadata.isEmpty()) return null
        return objectMapper.writeValueAsString(metadata)
    }

    fun deserializeMetadata(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            objectMapper.readValue(json)
        } catch (e: Exception) {
            logger.warn("Unreadable audit metadata payload, rendering entry without metadata", e)
            emptyMap()
        }
    }
}
