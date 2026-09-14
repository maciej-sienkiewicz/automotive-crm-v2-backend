package pl.detailing.crm.communication.infrastructure

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.CommunicationStatus
import java.time.Instant
import java.util.UUID

/**
 * Zapisuje wpisy dziennika komunikacji w WŁASNEJ transakcji.
 *
 * Osobny bean — celowo, tak samo jak [pl.detailing.crm.audit.infrastructure.AuditLogWriter].
 * `@Transactional` działa przez proxy, więc na wywołaniu w obrębie tego samego beana
 * (self-invocation) nie robi NIC. Żeby wywołujący mógł bezpiecznie złapać błąd zapisu, granica
 * transakcji musi być osobną metodą osobnego beana: gdy zapis narusza ograniczenie, interceptor
 * tej metody wycofuje tę (i tylko tę) transakcję i wyrzuca wyjątek do wywołującego — już PO jej
 * zamknięciu. Wywołujący łapie go poza granicą transakcji, więc nie ma ani zatrutej transakcji,
 * ani commitu oznaczonego jako rollback-only (który leciał w górę `UnexpectedRollbackException`).
 *
 * Dla kontrastu `@Transactional` na metodzie, WEWNĄTRZ której łapiemy wyjątek, nie izoluje:
 * `catch` połyka błąd, metoda kończy się „normalnie", a interceptor i tak próbuje scommitować
 * transakcję rollback-only i wywraca całą operację biznesową. Dokładnie tak było wcześniej
 * w [pl.detailing.crm.communication.CommunicationLogService].
 */
@Component
class CommunicationLogWriter(
    private val repository: CommunicationLogJpaRepository,
) {
    /**
     * Nowy wpis. Flush wewnątrz tej transakcji, żeby ewentualne naruszenie ograniczenia wyszło
     * TU (i wycofało tę transakcję), a nie dopiero przy commicie transakcji wywołującego.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun persist(entity: CommunicationLogEntity) {
        repository.saveAndFlush(entity)
    }

    /** Domknięcie wpisu QUEUED (QUEUED -> SENT/FAILED) — również w osobnej transakcji. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun resolveQueued(
        queuedMessageId: UUID,
        from: CommunicationStatus,
        to: CommunicationStatus,
        errorMessage: String?,
        at: Instant,
    ) {
        repository.resolveQueued(queuedMessageId, from, to, errorMessage, at)
    }
}
