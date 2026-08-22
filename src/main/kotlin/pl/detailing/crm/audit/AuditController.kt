package pl.detailing.crm.audit

import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditActorType
import pl.detailing.crm.audit.domain.AuditChannel
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditSeverity
import pl.detailing.crm.audit.feed.AuditActionFilterOption
import pl.detailing.crm.audit.feed.AuditFeedResponse
import pl.detailing.crm.audit.feed.AuditFilterOption
import pl.detailing.crm.audit.feed.AuditFilterOptionsResponse
import pl.detailing.crm.audit.feed.GetAuditFeedCommand
import pl.detailing.crm.audit.feed.GetAuditFeedHandler
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
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
    private val getAuditFeedHandler: GetAuditFeedHandler
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
