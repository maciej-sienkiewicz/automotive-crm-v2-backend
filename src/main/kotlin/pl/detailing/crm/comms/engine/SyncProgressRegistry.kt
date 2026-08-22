package pl.detailing.crm.comms.engine

import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Migawka postępu: ile wiadomości zaplanowano i ile już przejrzano. */
data class SyncProgressSnapshot(val total: Int, val processed: Int)

/**
 * Postęp pierwszej synchronizacji skrzynki — w pamięci, nie w bazie.
 *
 * Pierwszy import potrafi trwać minuty i przez ten czas interfejs pokazuje pasek
 * postępu zamiast lawiny powiadomień. To jest stan procesu, nie fakt o świecie:
 * po restarcie aplikacji synchronizacja i tak startuje od nowa, więc utrwalanie go
 * dawałoby tylko możliwość pokazania nieaktualnego paska.
 *
 * „Total" rośnie w trakcie (INBOX najpierw, potem Wysłane) — pasek potrafi przez to
 * raz drgnąć wstecz. To uczciwsze niż zgadywanie sumy z góry: procent liczony z
 * liczby, którą się zmyśliło, doskakuje do 100% i stoi.
 */
@Component
class SyncProgressRegistry {

    private class Progress {
        val total = AtomicInteger(0)
        val processed = AtomicInteger(0)
    }

    private val byAccount = ConcurrentHashMap<UUID, Progress>()

    /** Kolejna paczka wiadomości do przejrzenia (jeden folder). */
    fun addPlanned(accountId: UUID, count: Int) {
        if (count <= 0) return
        byAccount.computeIfAbsent(accountId) { Progress() }.total.addAndGet(count)
    }

    /** Jedna wiadomość przejrzana (zapisana, zdeduplikowana albo pominięta — każda liczy się tak samo). */
    fun tick(accountId: UUID) {
        byAccount[accountId]?.processed?.incrementAndGet()
    }

    /** Przebieg zakończony — pasek znika niezależnie od wyniku. */
    fun finish(accountId: UUID) {
        byAccount.remove(accountId)
    }

    fun snapshot(accountId: UUID): SyncProgressSnapshot? =
        byAccount[accountId]?.let { SyncProgressSnapshot(it.total.get(), it.processed.get()) }
}
