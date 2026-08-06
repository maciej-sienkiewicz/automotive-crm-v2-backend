package pl.detailing.crm.audit.infrastructure

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Persists an audit entry in its own transaction.
 *
 * This exists as a separate bean on purpose. `@Transactional` is applied by a proxy, so it
 * has no effect on self-invocation — and it does nothing useful on a `suspend` function
 * either, because Spring only drives suspending transactional methods through a
 * `ReactiveTransactionManager`, which a JPA application does not have. Annotating
 * `AuditService.log` therefore opened a transaction that committed before the coroutine
 * body ran, and `withContext(Dispatchers.IO)` moved the actual write onto a thread where
 * the transaction was not bound anyway.
 *
 * A plain method on a separate bean, invoked from inside the IO dispatcher, gets the
 * behaviour that was intended all along: the audit entry commits independently of the
 * business transaction that triggered it, so a rolled-back business operation cannot take
 * the record of it down as well.
 */
@Component
class AuditLogWriter(
    private val auditLogRepository: AuditLogRepository
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun write(entity: AuditLogEntity) {
        auditLogRepository.save(entity)
    }
}
