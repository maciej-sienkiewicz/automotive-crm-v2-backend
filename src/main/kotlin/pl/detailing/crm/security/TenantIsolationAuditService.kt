package pl.detailing.crm.security

import io.micrometer.core.instrument.MeterRegistry
import jakarta.persistence.EntityManager
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.observability.MetricsTags
import pl.detailing.crm.shared.StudioId
import java.util.UUID

/**
 * Detects cross-tenant access attempts: situations where an authenticated user
 * passes an entity ID that belongs to a different tenant (studio).
 *
 * Called from [pl.detailing.crm.config.GlobalExceptionHandler] whenever an
 * [pl.detailing.crm.shared.EntityNotFoundException] is thrown for an authenticated request.
 * If the not-found ID actually exists in another tenant's data, the incident is:
 *   1. WARN-logged with full context (userId, studioId, target entity, IP)
 *   2. Recorded in the audit_logs table (module=SECURITY, action=CROSS_TENANT_ACCESS_ATTEMPT)
 *   3. Counted as a Prometheus metric for alerting
 *
 * The HTTP response remains 404 regardless of outcome — revealing that an ID exists
 * in a different tenant would itself be an information leak.
 *
 * Checked tables: customers, vehicles, visits, appointments.
 * Extend [TENANT_TABLES] to cover additional entities if needed.
 */
@Service
class TenantIsolationAuditService(
    private val entityManager: EntityManager,
    private val auditService: AuditService,
    private val meterRegistry: MeterRegistry
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val UUID_PATTERN = Regex(
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
        RegexOption.IGNORE_CASE
    )

    companion object {
        private val TENANT_TABLES = listOf(
            "customers"    to "customer",
            "vehicles"     to "vehicle",
            "visits"       to "visit",
            "appointments" to "appointment"
        )
    }

    /**
     * Inspects the request URI and query parameters for UUID-shaped tokens,
     * then checks whether any of them belong to a different tenant.
     * Stops after the first cross-tenant hit to avoid unnecessary DB round trips.
     */
    fun checkRequest(request: HttpServletRequest, user: UserPrincipal) {
        val uuids = extractUuids(request)
        if (uuids.isEmpty()) return

        for (uuid in uuids) {
            if (probeAndLog(uuid, user, request)) return
        }
    }

    private fun probeAndLog(
        id: UUID,
        user: UserPrincipal,
        request: HttpServletRequest
    ): Boolean {
        for ((table, entityType) in TENANT_TABLES) {
            val actualStudioId = queryStudioId(table, id) ?: continue

            if (actualStudioId == user.studioId) return false

            log.warn(
                "CROSS_TENANT_ACCESS userId={} email={} studioId={} attempted {} id={} owned by studioId={} path={} method={}",
                user.userId, user.email, user.studioId,
                entityType, id, actualStudioId,
                request.requestURI, request.method
            )

            meterRegistry.counter(
                MetricsTags.SECURITY_CROSS_TENANT_ATTEMPT,
                MetricsTags.TAG_STUDIO_ID, user.studioId.toString(),
                "entity_type", entityType
            ).increment()

            auditService.logSync(
                LogAuditCommand(
                    studioId = user.studioId,
                    userId = user.userId,
                    userDisplayName = user.fullName,
                    module = AuditModule.SECURITY,
                    entityId = id.toString(),
                    entityDisplayName = "$entityType in studioId=$actualStudioId",
                    action = AuditAction.CROSS_TENANT_ACCESS_ATTEMPT,
                    metadata = mapOf(
                        "actual_studio_id"  to actualStudioId.toString(),
                        "entity_type"       to entityType,
                        "request_path"      to request.requestURI,
                        "request_method"    to request.method,
                        "user_email"        to user.email,
                        "user_role"         to if (user.isOwner) "OWNER" else "USER"
                    )
                )
            )
            return true
        }
        return false
    }

    @Suppress("UNCHECKED_CAST")
    private fun queryStudioId(table: String, id: UUID): StudioId? {
        return try {
            val rows = entityManager.createNativeQuery(
                "SELECT studio_id FROM $table WHERE id = :id LIMIT 1"
            ).setParameter("id", id).resultList as List<*>

            val raw = rows.firstOrNull() ?: return null
            StudioId(UUID.fromString(raw.toString()))
        } catch (_: Exception) {
            null
        }
    }

    private fun extractUuids(request: HttpServletRequest): List<UUID> {
        val input = buildString {
            append(request.requestURI)
            request.parameterMap.values.forEach { values ->
                values.forEach { append(' ').append(it) }
            }
        }
        return UUID_PATTERN.findAll(input)
            .mapNotNull { runCatching { UUID.fromString(it.value) }.getOrNull() }
            .distinctBy { it }
            .toList()
    }
}
