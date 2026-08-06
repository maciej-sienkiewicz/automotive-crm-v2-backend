package pl.detailing.crm.audit

import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditActorType
import pl.detailing.crm.audit.domain.AuditChannel
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditSeverity
import pl.detailing.crm.audit.entity.GetEntityAuditLogsCommand
import pl.detailing.crm.audit.entity.GetEntityAuditLogsHandler
import pl.detailing.crm.audit.feed.AuditActionFilterOption
import pl.detailing.crm.audit.feed.AuditFeedResponse
import pl.detailing.crm.audit.feed.AuditFilterOption
import pl.detailing.crm.audit.feed.AuditFilterOptionsResponse
import pl.detailing.crm.audit.feed.GetAuditFeedCommand
import pl.detailing.crm.audit.feed.GetAuditFeedHandler
import pl.detailing.crm.audit.list.AuditLogListResult
import pl.detailing.crm.audit.list.ListAuditLogsCommand
import pl.detailing.crm.audit.list.ListAuditLogsHandler
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.util.UUID

/**
 * Company-wide activity history.
 *
 * Guarded by [Permission.AUDIT_VIEW] at class level. Studio owners bypass the check, which
 * is the intended default — this is the owner's oversight tool, and until now every
 * authenticated employee of the studio could read the whole log, payroll and security
 * events included.
 */
@RestController
@RequestMapping("/api/v1/audit")
@RequiresPermission(Permission.AUDIT_VIEW)
class AuditController(
    private val getAuditFeedHandler: GetAuditFeedHandler,
    private val listAuditLogsHandler: ListAuditLogsHandler,
    private val getEntityAuditLogsHandler: GetEntityAuditLogsHandler
) {

    /**
     * The activity feed: everything that happened in the studio, newest first, with each
     * entry already rendered for display.
     *
     * ```
     * GET /api/v1/audit/feed
     *   ?limit=30&cursor=<nextCursor from the previous page>
     *   &modules=VISIT,APPOINTMENT      // any of
     *   &actions=CREATE,PHOTO_ADDED     // any of
     *   &actorTypes=EMPLOYEE,CUSTOMER   // who acted
     *   &severities=HIGH,CRITICAL       // "only what matters"
     *   &channels=PUBLIC_LINK
     *   &actorId=<user or customer id>
     *   &customerId=…&vehicleId=…&visitId=…   // everything around one object
     *   &correlationId=…                      // one user gesture
     *   &module=VISIT&entityId=…              // history of a single entity
     *   &from=2026-02-01T00:00:00Z&to=2026-02-28T23:59:59Z
     *   &search=kowalski
     * ```
     *
     * Paging is by cursor: send back [AuditFeedResponse.nextCursor] to get the next page,
     * and stop when [AuditFeedResponse.hasMore] is false.
     */
    @GetMapping("/feed")
    fun getFeed(
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) modules: String?,
        @RequestParam(required = false) actions: String?,
        @RequestParam(required = false) actorTypes: String?,
        @RequestParam(required = false) severities: String?,
        @RequestParam(required = false) channels: String?,
        @RequestParam(required = false) actorId: String?,
        @RequestParam(required = false) customerId: String?,
        @RequestParam(required = false) vehicleId: String?,
        @RequestParam(required = false) visitId: String?,
        @RequestParam(required = false) correlationId: String?,
        @RequestParam(required = false) module: String?,
        @RequestParam(required = false) entityId: String?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) search: String?
    ): ResponseEntity<AuditFeedResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = GetAuditFeedCommand(
            studioId = principal.studioId,
            limit = limit ?: GetAuditFeedCommand.DEFAULT_LIMIT,
            cursor = cursor,
            modules = parseEnumList(modules, "modules") { AuditModule.valueOf(it) },
            actions = parseEnumList(actions, "actions") { AuditAction.valueOf(it) },
            actorTypes = parseEnumList(actorTypes, "actorTypes") { AuditActorType.valueOf(it) },
            severities = parseEnumList(severities, "severities") { AuditSeverity.valueOf(it) },
            channels = parseEnumList(channels, "channels") { AuditChannel.valueOf(it) },
            actorId = parseUuid(actorId, "actorId"),
            customerId = parseUuid(customerId, "customerId"),
            vehicleId = parseUuid(vehicleId, "vehicleId"),
            visitId = parseUuid(visitId, "visitId"),
            correlationId = parseUuid(correlationId, "correlationId"),
            module = module?.trim()?.takeIf { it.isNotEmpty() }?.let { parseEnum(it, "module") { v -> AuditModule.valueOf(v) } },
            entityId = entityId?.trim()?.takeIf { it.isNotEmpty() },
            from = parseInstant(from, "from"),
            to = parseInstant(to, "to"),
            search = search
        )

        ResponseEntity.ok(getAuditFeedHandler.handle(command))
    }

    /**
     * Options for the filter bar, labels included, so the UI does not ship its own copy of
     * the enum translations.
     *
     * GET /api/v1/audit/filters
     */
    @GetMapping("/filters")
    fun getFilterOptions(): ResponseEntity<AuditFilterOptionsResponse> = ResponseEntity.ok(
        AuditFilterOptionsResponse(
            modules = AuditModule.entries.map { AuditFilterOption(it.name, it.label, it.icon.name) },
            actions = AuditAction.entries.map {
                AuditActionFilterOption(it.name, it.label, it.icon.name, it.severity.name)
            },
            actorTypes = AuditActorType.entries.map { AuditFilterOption(it.name, it.label) },
            severities = AuditSeverity.entries
                .sortedByDescending { it.weight }
                .map { AuditFilterOption(it.name, it.label) },
            channels = AuditChannel.entries.map { AuditFilterOption(it.name, it.label) }
        )
    )

    /**
     * Page-numbered history, raw field changes, no rendering.
     *
     * Superseded by [getFeed] — kept so existing clients keep working. Unlike the feed it
     * pays for a `count(*)` on every request and cannot express the actor, severity or
     * business-context filters.
     */
    @Deprecated("Use GET /api/v1/audit/feed")
    @GetMapping("/activity")
    fun getActivityHistory(
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) modules: String?,
        @RequestParam(required = false) actions: String?,
        @RequestParam(required = false) userId: String?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?
    ): ResponseEntity<AuditActivityResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = ListAuditLogsCommand(
            studioId = principal.studioId,
            page = maxOf(1, page),
            pageSize = size.coerceIn(1, 100),
            modules = parseEnumList(modules, "modules") { AuditModule.valueOf(it) },
            actions = parseEnumList(actions, "actions") { AuditAction.valueOf(it) },
            userId = parseUuid(userId, "userId")?.let { UserId(it) },
            from = parseInstant(from, "from"),
            to = parseInstant(to, "to")
        )

        ResponseEntity.ok(mapToResponse(listAuditLogsHandler.handle(command)))
    }

    /**
     * History of a single entity — the activity tab on a customer, vehicle or visit card.
     *
     * GET /api/v1/audit/VEHICLE/550e8400-e29b-41d4-a716-446655440000
     *
     * Superseded by `GET /api/v1/audit/feed?module=…&entityId=…`, which returns the same
     * rows already rendered.
     */
    @Deprecated("Use GET /api/v1/audit/feed with module and entityId")
    @GetMapping("/{module}/{entityId}")
    fun getEntityAuditLogs(
        @PathVariable module: String,
        @PathVariable entityId: String,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "50") size: Int
    ): ResponseEntity<AuditActivityResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val command = GetEntityAuditLogsCommand(
            studioId = principal.studioId,
            module = parseEnum(module, "module") { AuditModule.valueOf(it) },
            entityId = entityId,
            page = maxOf(1, page),
            pageSize = size.coerceIn(1, 100)
        )

        ResponseEntity.ok(mapToResponse(getEntityAuditLogsHandler.handle(command)))
    }

    private fun mapToResponse(result: AuditLogListResult): AuditActivityResponse = AuditActivityResponse(
        items = result.items.map { item ->
            AuditActivityItem(
                id = item.id,
                userId = item.userId,
                userDisplayName = item.userDisplayName,
                module = item.module,
                entityId = item.entityId,
                entityDisplayName = item.entityDisplayName,
                action = item.action,
                changes = item.changes.map { change ->
                    AuditFieldChangeResponse(change.field, change.oldValue, change.newValue)
                },
                metadata = item.metadata,
                createdAt = item.createdAt
            )
        },
        pagination = AuditPaginationResponse(
            total = result.total,
            page = result.page,
            pageSize = result.pageSize,
            totalPages = result.totalPages
        )
    )

    // ── Parameter parsing ───────────────────────────────────────────────────
    // Unparseable values are rejected rather than dropped. Silently ignoring an unknown
    // filter value returns *more* rows than asked for, which reads as "the filter does not
    // work" and is the worst possible answer for an oversight tool.

    private fun <T> parseEnumList(raw: String?, parameter: String, parse: (String) -> T): List<T>? =
        raw?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }
            ?.map { parseEnum(it, parameter, parse) }

    private fun <T> parseEnum(raw: String, parameter: String, parse: (String) -> T): T = try {
        parse(raw.trim().uppercase())
    } catch (e: IllegalArgumentException) {
        throw ValidationException("Nieprawidłowa wartość parametru '$parameter': $raw")
    }

    private fun parseUuid(raw: String?, parameter: String): UUID? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            UUID.fromString(value)
        } catch (e: IllegalArgumentException) {
            throw ValidationException("Nieprawidłowy identyfikator w parametrze '$parameter': $raw")
        }
    }

    private fun parseInstant(raw: String?, parameter: String): Instant? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            Instant.parse(value)
        } catch (e: java.time.format.DateTimeParseException) {
            throw ValidationException("Nieprawidłowa data w parametrze '$parameter' (oczekiwano ISO-8601): $raw")
        }
    }
}

// ── Legacy response DTOs ────────────────────────────────────────────────────
// Used only by the deprecated endpoints above. The feed has its own, richer shape in
// pl.detailing.crm.audit.feed.

data class AuditActivityResponse(
    val items: List<AuditActivityItem>,
    val pagination: AuditPaginationResponse
)

data class AuditActivityItem(
    val id: String,
    val userId: String,
    val userDisplayName: String,
    val module: String,
    val entityId: String,
    val entityDisplayName: String?,
    val action: String,
    val changes: List<AuditFieldChangeResponse>,
    val metadata: Map<String, String>,
    val createdAt: Instant
)

data class AuditFieldChangeResponse(
    val field: String,
    val oldValue: String?,
    val newValue: String?
)

data class AuditPaginationResponse(
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int
)
