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

data class HistoryFieldChange(
    val field: String,
    val oldValue: String?,
    val newValue: String?
)

data class LeadStatusHistoryEntry(
    val changedAt: Instant,
    val action: String,
    val changedByUserId: String,
    val changedByName: String,
    val changes: List<HistoryFieldChange>
)

private val HISTORY_ACTIONS = setOf(
    AuditAction.CREATE,
    AuditAction.STATUS_CHANGE,
    AuditAction.LEAD_CONFIRMED,
    AuditAction.LEAD_COMPLETED,
    AuditAction.LEAD_LOST,
    AuditAction.LEAD_NO_SHOW,
    AuditAction.LEAD_CONVERTED,
    AuditAction.LEAD_ABANDONED,
    AuditAction.LEAD_APPOINTMENT_CREATED,
    AuditAction.LEAD_USER_ASSIGNED,
    AuditAction.LEAD_CUSTOMER_ASSIGNED,
    AuditAction.LEAD_LOST_REASON_UPDATED,
    AuditAction.LEAD_QUOTE_UPDATED,
    AuditAction.LEAD_COMMENT_ADDED,
    AuditAction.LEAD_COMMENT_UPDATED,
    AuditAction.LEAD_COMMENT_DELETED,
    AuditAction.LEAD_ESTIMATION_COMPLETED,
    AuditAction.LEAD_SPLIT,
    AuditAction.LEAD_MERGED
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
                .filter { it.action in HISTORY_ACTIONS }
                .map { entry ->
                    LeadStatusHistoryEntry(
                        changedAt = entry.createdAt,
                        action = entry.action.name,
                        changedByUserId = entry.userId.toString(),
                        changedByName = entry.userDisplayName,
                        changes = parseChanges(entry.changes)
                    )
                }
        }

    private fun parseChanges(changesJson: String?): List<HistoryFieldChange> {
        if (changesJson.isNullOrBlank()) return emptyList()
        return try {
            val fieldPattern = Regex(""""field"\s*:\s*"([^"]+)"""")
            val oldPattern = Regex(""""oldValue"\s*:\s*("([^"]*)"|(null)|\d+)""")
            val newPattern = Regex(""""newValue"\s*:\s*("([^"]*)"|(null)|\d+)""")

            val fields = fieldPattern.findAll(changesJson).map { it.groupValues[1] }.toList()
            val olds = oldPattern.findAll(changesJson).map { m ->
                val quoted = m.groupValues[2]
                val raw = m.groupValues[1]
                when {
                    raw == "null" -> null
                    quoted.isNotEmpty() -> quoted
                    else -> raw
                }
            }.toList()
            val news = newPattern.findAll(changesJson).map { m ->
                val quoted = m.groupValues[2]
                val raw = m.groupValues[1]
                when {
                    raw == "null" -> null
                    quoted.isNotEmpty() -> quoted
                    else -> raw
                }
            }.toList()

            fields.mapIndexed { i, field ->
                HistoryFieldChange(
                    field = field,
                    oldValue = olds.getOrNull(i),
                    newValue = news.getOrNull(i)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
