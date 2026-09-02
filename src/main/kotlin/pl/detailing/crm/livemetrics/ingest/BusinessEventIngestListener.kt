package pl.detailing.crm.livemetrics.ingest

import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import pl.detailing.crm.livemetrics.domain.BusinessEvent

/**
 * Most między Spring ApplicationEvents a kolejką ingestu.
 *
 * AFTER_COMMIT: zdarzenie liczymy dopiero, gdy rezerwacja/wizyta naprawdę istnieje.
 * fallbackExecution: handlery na `Dispatchers.IO` nie mają związanej transakcji —
 * wtedy zdarzenie idzie natychmiast (po `repository.save()` w autocommicie to jest
 * ten sam moment).
 */
@Component
class BusinessEventIngestListener(
    private val worker: BusinessEventIngestWorker
) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onBusinessEvent(event: BusinessEvent) {
        worker.accept(event)
    }
}
