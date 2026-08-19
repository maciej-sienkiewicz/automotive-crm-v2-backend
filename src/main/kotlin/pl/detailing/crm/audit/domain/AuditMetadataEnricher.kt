package pl.detailing.crm.audit.domain

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.infrastructure.AuditLogRepository
import java.util.UUID

/**
 * The transactional half of [AuditService.enrichLatestEntry], split into its own bean
 * so the transaction boundary sits INSIDE the caller's try/catch rather than around it.
 *
 * Two reasons it is not a private method on AuditService:
 * - a self-call would bypass the Spring proxy, leaving the @Modifying update with no
 *   transaction at all;
 * - REQUIRES_NEW suspends the caller's transaction, so a failed merge rolls back only
 *   this statement. The business operation that asked for the enrichment (a signature
 *   flow, a check-in) commits regardless — feed cosmetics must never take it down.
 */
@Component
class AuditMetadataEnricher(
    private val auditLogRepository: AuditLogRepository
) {

    /** @return true when the row was found and its metadata merged. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun merge(auditLogId: UUID, patchJson: String): Boolean =
        auditLogRepository.mergeMetadata(auditLogId, patchJson) > 0
}
