package pl.detailing.crm.audit.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * Write side of the audit log.
 *
 * All reads go through [AuditFeedQueryRepository]: the derived-query variants that used to
 * live here (by module, by action, by user, by date range) were never called — the list
 * handler always took the combined-filter path — and every combination of filters would
 * have needed its own method. Composed predicates cover them all.
 */
@Repository
interface AuditLogRepository : JpaRepository<AuditLogEntity, UUID>
