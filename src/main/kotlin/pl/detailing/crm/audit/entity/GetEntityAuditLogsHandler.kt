package pl.detailing.crm.audit.entity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.infrastructure.AuditFeedFilters
import pl.detailing.crm.audit.infrastructure.AuditFeedQueryRepository
import pl.detailing.crm.audit.list.AuditLogListResult
import pl.detailing.crm.audit.list.toLegacyListItem
import pl.detailing.crm.shared.StudioId
import kotlin.math.ceil

data class GetEntityAuditLogsCommand(
    val studioId: StudioId,
    val module: AuditModule,
    val entityId: String,
    val page: Int = 1,
    val pageSize: Int = 50
)

/**
 * History of one entity, page-numbered — the "activity" tab on a customer, vehicle or
 * visit card. New clients should prefer `GET /api/v1/audit/feed?module=…&entityId=…`,
 * which returns the same rows already rendered for display.
 */
@Service
class GetEntityAuditLogsHandler(
    private val auditFeedQueryRepository: AuditFeedQueryRepository,
    private val auditService: AuditService
) {

    suspend fun handle(command: GetEntityAuditLogsCommand): AuditLogListResult = withContext(Dispatchers.IO) {
        val page = maxOf(1, command.page)
        val pageSize = command.pageSize.coerceIn(1, 100)

        val filters = AuditFeedFilters(
            studioId = command.studioId.value,
            module = command.module,
            entityId = command.entityId
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
