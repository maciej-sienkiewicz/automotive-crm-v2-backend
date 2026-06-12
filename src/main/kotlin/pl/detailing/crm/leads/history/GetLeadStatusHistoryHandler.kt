package pl.detailing.crm.leads.history

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.infrastructure.AuditLogRepository
import pl.detailing.crm.shared.LeadId
import pl.detailing.crm.shared.StudioId
import java.time.Instant

data class GetLeadStatusHistoryQuery(
    val leadId: LeadId,
    val studioId: StudioId
)

data class LeadStatusHistoryEntry(
    val changedAt: Instant,
    val action: String,
    val changedByUserId: String,
    val changedByName: String,
    val fromStatus: String?,
    val toStatus: String?
)

private val STATUS_ACTIONS = setOf(
    AuditAction.CREATE,
    AuditAction.STATUS_CHANGE,
    AuditAction.LEAD_CONFIRMED,
    AuditAction.LEAD_COMPLETED,
    AuditAction.LEAD_LOST,
    AuditAction.LEAD_NO_SHOW,
    AuditAction.LEAD_CONVERTED,
    AuditAction.LEAD_ABANDONED,
    AuditAction.LEAD_APPOINTMENT_CREATED
)

@Service
class GetLeadStatusHistoryHandler(
    private val auditLogRepository: AuditLogRepository
) {
    @Transactional(readOnly = true)
    suspend fun handle(query: GetLeadStatusHistoryQuery): List<LeadStatusHistoryEntry> =
        withContext(Dispatchers.IO) {
            val pageable = PageRequest.of(0, 500, Sort.by(Sort.Direction.ASC, "createdAt"))

            auditLogRepository.findByStudioIdAndModuleAndEntityId(
                studioId = query.studioId.value,
                module = AuditModule.LEAD,
                entityId = query.leadId.value.toString(),
                pageable = pageable
            ).content
                .filter { it.action in STATUS_ACTIONS }
                .map { entry ->
                    val changes = parseChanges(entry.changes)
                    LeadStatusHistoryEntry(
                        changedAt = entry.createdAt,
                        action = entry.action.name,
                        changedByUserId = entry.userId.toString(),
                        changedByName = entry.userDisplayName,
                        fromStatus = changes["status"]?.first,
                        toStatus = changes["status"]?.second
                    )
                }
        }

    private fun parseChanges(changesJson: String?): Map<String, Pair<String?, String?>> {
        if (changesJson.isNullOrBlank()) return emptyMap()
        return try {
            val result = mutableMapOf<String, Pair<String?, String?>>()
            val fieldPattern = Regex(""""field"\s*:\s*"([^"]+)"""")
            val oldPattern = Regex(""""oldValue"\s*:\s*"?([^",}\]]*)"?""")
            val newPattern = Regex(""""newValue"\s*:\s*"?([^",}\]]*)"?""")

            val fields = fieldPattern.findAll(changesJson).map { it.groupValues[1] }.toList()
            val olds = oldPattern.findAll(changesJson).map { it.groupValues[1].takeIf { v -> v != "null" } }.toList()
            val news = newPattern.findAll(changesJson).map { it.groupValues[1].takeIf { v -> v != "null" } }.toList()

            fields.forEachIndexed { i, field ->
                result[field] = Pair(olds.getOrNull(i), news.getOrNull(i))
            }
            result
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
