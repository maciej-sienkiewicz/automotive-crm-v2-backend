package pl.detailing.crm.comms

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import pl.detailing.crm.comms.domain.CommMessageReadEvent
import pl.detailing.crm.comms.domain.CommThreadChangedEvent
import pl.detailing.crm.shared.CommMessageReadPayload
import pl.detailing.crm.shared.CommThreadUpdatedPayload
import pl.detailing.crm.shared.DashboardEvent
import pl.detailing.crm.shared.DashboardEventType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Pushes comms events to the studio's dashboard topic. Payloads are id-only — the
 * frontend refetches over REST — so nothing personal rides on the broadcast and the
 * push can stay best-effort (a missed event only delays the UI until the next fetch).
 *
 * AFTER_COMMIT with fallbackExecution matches WebSocketEventBridge: sync engine code
 * publishes from scheduler/IDLE threads that are not always transaction-bound.
 *
 * Zdarzenia tego samego wątku z krótkiego okna ([COALESCE_MS]) idą jednym pushem.
 * Serwer publikuje zdarzenie na KAŻDĄ wiadomość: otwarcie wątku z dziesięcioma
 * nieprzeczytanymi to dziesięć „przeczytano", paczka z importu to kilka wiadomości
 * jednego wątku naraz. Każdy push kazał każdej otwartej karcie w studiu pobrać listę
 * wątków, licznik i leady od nowa - i biuro wpadało w „Przekroczono limit żądań".
 * Front i tak odświeża dane przez REST, więc drugi push o tym samym wątku w tej samej
 * chwili nie niesie nic nowego.
 */
@Component
class CommsWebSocketBridge(
    private val messagingTemplate: SimpMessagingTemplate
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val pending = ConcurrentHashMap<String, DashboardEvent<*>>()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "comms-ws-coalesce").apply { isDaemon = true }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onThreadChanged(event: CommThreadChangedEvent) {
        enqueue("${event.studioId}:thread:${event.threadId}", event.studioId) { previous ->
            // „Nowa wiadomość" wygrywa: jedna przychodząca w paczce wystarczy na powiadomienie.
            val wasNew = (previous?.payload as? CommThreadUpdatedPayload)?.newMessage == true
            DashboardEvent(
                type = DashboardEventType.COMM_THREAD_UPDATED,
                payload = CommThreadUpdatedPayload(
                    threadId = event.threadId.toString(),
                    newMessage = event.newMessage || wasNew
                )
            )
        }
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onMessageRead(event: CommMessageReadEvent) {
        // Front z tego zdarzenia bierze tylko wątek - kolejne wiadomości tego samego
        // wątku zastępują poprzednią zamiast wysyłać się osobno.
        enqueue("${event.studioId}:read:${event.threadId}", event.studioId) {
            DashboardEvent(
                type = DashboardEventType.COMM_MESSAGE_READ,
                payload = CommMessageReadPayload(
                    threadId = event.threadId.toString(),
                    messageId = event.messageId.toString(),
                    readSource = event.readSource.name
                )
            )
        }
    }

    private fun enqueue(key: String, studioId: UUID, merge: (DashboardEvent<*>?) -> DashboardEvent<*>) {
        var first = false
        pending.compute(key) { _, previous ->
            if (previous == null) first = true
            merge(previous)
        }
        if (first) {
            scheduler.schedule({ pending.remove(key)?.let { send(studioId, it) } }, COALESCE_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun send(studioId: UUID, event: DashboardEvent<*>) {
        val destination = "/topic/studio.$studioId.dashboard"
        try {
            messagingTemplate.convertAndSend(destination, event)
        } catch (e: Exception) {
            log.error("[COMMS-WS] Failed to send {} to {}: {}", event.type, destination, e.message)
        }
    }

    /** Oddaje zebrane zdarzenia od razu - przy zamykaniu aplikacji i w testach. */
    internal fun flushNow() {
        pending.keys.toList().forEach { key ->
            pending.remove(key)?.let { event -> send(UUID.fromString(key.substringBefore(':')), event) }
        }
    }

    @PreDestroy
    fun shutdown() {
        flushNow()
        scheduler.shutdownNow()
    }

    internal companion object {
        const val COALESCE_MS = 750L
    }
}
