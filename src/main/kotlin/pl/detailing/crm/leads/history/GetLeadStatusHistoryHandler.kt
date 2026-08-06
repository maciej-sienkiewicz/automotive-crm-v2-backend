package pl.detailing.crm.leads.history

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.infrastructure.AuditFeedFilters
import pl.detailing.crm.audit.infrastructure.AuditFeedQueryRepository
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

/** Status-relevant actions; comments and assignments included, routine edits not. */
private val HISTORY_ACTIONS = listOf(
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
    AuditAction.LEAD_MERGED,
    AuditAction.LEAD_CUSTOMER_ANSWERED
)

private const val MAX_HISTORY_ENTRIES = 500

@Service
class GetLeadStatusHistoryHandler(
    private val auditFeedQueryRepository: AuditFeedQueryRepository,
    private val auditService: AuditService
) {
    @Transactional(readOnly = true)
    suspend fun handle(query: GetLeadStatusHistoryQuery): List<LeadStatusHistoryEntry> =
        withContext(Dispatchers.IO) {
            auditFeedQueryRepository.findOffsetPage(
                filters = AuditFeedFilters(
                    studioId = query.studioId.value,
                    module = AuditModule.LEAD,
                    entityId = query.leadId.value.toString(),
                    // Filtered in SQL rather than after loading, so a noisy lead cannot push
                    // its own status changes past the row limit.
                    actions = HISTORY_ACTIONS
                ),
                offset = 0,
                limit = MAX_HISTORY_ENTRIES
            )
                // The query returns newest-first; the timeline reads oldest-first.
                .reversed()
                .map { entry ->
                    LeadStatusHistoryEntry(
                        changedAt = entry.createdAt,
                        action = entry.action.name,
                        changedByUserId = entry.userId?.toString() ?: "",
                        changedByName = entry.userDisplayName,
                        // Was hand-parsed with regexes over the raw JSON; the audit service
                        // deserializes the same payload properly and tolerates bad rows.
                        changes = auditService.deserializeChanges(entry.changes).map { change ->
                            HistoryFieldChange(change.field, change.oldValue, change.newValue)
                        }
                    )
                }
        }
}
