package pl.detailing.crm.audit.list

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.audit.infrastructure.AuditLogRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant

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

@Service
class ListAuditLogsHandler(
    private val auditLogRepository: AuditLogRepository,
    private val auditService: AuditService
) {

    suspend fun handle(command: ListAuditLogsCommand): AuditLogListResult = withContext(Dispatchers.IO) {
        val pageable = PageRequest.of(command.page - 1, command.pageSize)

        val hasModuleFilter = !command.modules.isNullOrEmpty()
        val hasActionFilter = !command.actions.isNullOrEmpty()
        val hasUserFilter = command.userId != null
        val hasDateFilter = command.from != null || command.to != null

        val page = when {
            hasModuleFilter || hasActionFilter || hasUserFilter || hasDateFilter -> {
                auditLogRepository.findByStudioIdWithFilters(
                    studioId = command.studioId.value,
                    modules = if (hasModuleFilter) command.modules else null,
                    actions = if (hasActionFilter) command.actions else null,
                    userId = command.userId?.value,
                    from = command.from,
                    to = command.to,
                    pageable = pageable
                )
            }
            else -> {
                auditLogRepository.findByStudioId(
                    studioId = command.studioId.value,
                    pageable = pageable
                )
            }
        }

        AuditLogListResult(
            items = page.content.map { entity ->
                AuditLogListItem(
                    id = entity.id.toString(),
                    userId = entity.userId.toString(),
                    userDisplayName = entity.userDisplayName,
                    module = entity.module.name,
                    entityId = entity.entityId,
                    entityDisplayName = entity.entityDisplayName,
                    action = entity.action.name,
                    changes = auditService.deserializeChanges(entity.changes),
                    metadata = auditService.deserializeMetadata(entity.metadata),
                    createdAt = entity.createdAt
                )
            },
            total = page.totalElements.toInt(),
            page = command.page,
            pageSize = command.pageSize,
            totalPages = page.totalPages
        )
    }
}
