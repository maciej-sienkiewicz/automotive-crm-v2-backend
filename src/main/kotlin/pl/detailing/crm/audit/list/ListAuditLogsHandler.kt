package pl.detailing.crm.audit.list

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.FieldChange
import pl.detailing.crm.audit.infrastructure.AuditFeedFilters
import pl.detailing.crm.audit.infrastructure.AuditFeedQueryRepository
import pl.detailing.crm.audit.infrastructure.AuditLogEntity
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant
import kotlin.math.ceil

data class ListAuditLogsCommand(
    val studioId: StudioId,
    val page: Int = 1,
    val pageSize: Int = 20,
    val modules: List<AuditModule>? = null,
    val actions: List<AuditAction>? = null,
    val userId: UserId? = null,
    val from: Instant? = null,
    val to: Instant? = null
)

data class AuditLogListResult(
    val items: List<AuditLogListItem>,
    val total: Int,
    val page: Int,
    val pageSize: Int,
    val totalPages: Int
)

data class AuditLogListItem(
    val id: String,
    val userId: String,
    val userDisplayName: String,
    val module: String,
    val entityId: String,
    val entityDisplayName: String?,
    val action: String,
    val changes: List<FieldChange>,
    val metadata: Map<String, String>,
    val createdAt: Instant
)

/**
 * Page-numbered listing behind the legacy `GET /api/v1/audit/activity` endpoint.
 *
 * Kept so existing clients keep working; new work should use the cursor-paged feed
 * (`GET /api/v1/audit/feed`), which returns entries already rendered for display and does
 * not pay for a `count(*)` on every request.
 */
@Service
class ListAuditLogsHandler(
    private val auditFeedQueryRepository: AuditFeedQueryRepository,
    private val auditService: AuditService
) {

    suspend fun handle(command: ListAuditLogsCommand): AuditLogListResult = withContext(Dispatchers.IO) {
        val page = maxOf(1, command.page)
        val pageSize = command.pageSize.coerceIn(1, 100)

        val filters = AuditFeedFilters(
            studioId = command.studioId.value,
            modules = command.modules,
            actions = command.actions,
            actorId = command.userId?.value,
            from = command.from,
            to = command.to
        )

        val total = auditFeedQueryRepository.count(filters)
        val rows = auditFeedQueryRepository.findOffsetPage(
            filters = filters,
            offset = (page - 1) * pageSize,
            limit = pageSize
        )

        AuditLogListResult(
            items = rows.map { toLegacyListItem(it, auditService) },
            total = total.toInt(),
            page = page,
            pageSize = pageSize,
            totalPages = ceil(total.toDouble() / pageSize).toInt()
        )
    }
}

/** Shared by the two legacy, page-numbered endpoints. */
internal fun toLegacyListItem(entity: AuditLogEntity, auditService: AuditService): AuditLogListItem =
    AuditLogListItem(
        id = entity.id.toString(),
        // Empty rather than the string "null": automated actors have no id, and this field
        // is a non-nullable String in the legacy contract.
        userId = entity.userId?.toString() ?: "",
        userDisplayName = entity.userDisplayName,
        module = entity.module.name,
        entityId = entity.entityId,
        entityDisplayName = entity.entityDisplayName,
        action = entity.action.name,
        changes = auditService.deserializeChanges(entity.changes),
        metadata = auditService.deserializeMetadata(entity.metadata),
        createdAt = entity.createdAt
    )
