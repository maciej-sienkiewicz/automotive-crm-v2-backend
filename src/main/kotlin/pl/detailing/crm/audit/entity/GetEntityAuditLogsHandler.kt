package pl.detailing.crm.audit.entity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import pl.detailing.crm.audit.domain.*
import pl.detailing.crm.audit.infrastructure.AuditLogRepository
import pl.detailing.crm.audit.list.AuditLogListItem
import pl.detailing.crm.audit.list.AuditLogListResult
import pl.detailing.crm.shared.StudioId

data class GetEntityAuditLogsCommand(
    val studioId: StudioId,
    val module: AuditModule,
    val entityId: String,
    val page: Int = 1,
    val pageSize: Int = 50
)

@Service
class GetEntityAuditLogsHandler(
    private val auditLogRepository: AuditLogRepository,
    private val auditService: AuditService
) {

    suspend fun handle(command: GetEntityAuditLogsCommand): AuditLogListResult = withContext(Dispatchers.IO) {
        val pageable = PageRequest.of(command.page - 1, command.pageSize)

        val page = auditLogRepository.findByStudioIdAndModuleAndEntityId(
            studioId = command.studioId.value,
            module = command.module,
            entityId = command.entityId,
            pageable = pageable
        )

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
