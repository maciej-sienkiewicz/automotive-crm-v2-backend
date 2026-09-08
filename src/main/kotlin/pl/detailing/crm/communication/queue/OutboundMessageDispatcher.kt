package pl.detailing.crm.communication.queue

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import pl.detailing.crm.communication.CommunicationLogService
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.communication.window.SendWindow
import java.time.Instant
import java.util.UUID

/**
 * Wysyła wiadomości odłożone przez bramkę, gdy otworzy się okno wysyłki.
 *
 * Co minutę, ale tylko w godzinach z [SendWindow]: poza nimi tylko sprząta wiersze
 * zawieszone w SENDING po restarcie. Sama metoda [dispatch] NIE jest transakcyjna —
 * wywołanie dostawcy trwa i nie ma trzymać połączenia z bazą; każdy krok stanu
 * (zajęcie, wynik) ma własną transakcję w [OutboundMessageQueue].
 *
 * Wynik trafia w dwa miejsca: do wiersza kolejki (co się stało z tą próbą) i do
 * dziennika komunikacji ([CommunicationLogService.recordQueuedOutcome]), gdzie wpis
 * QUEUED założony przy odłożeniu zmienia się w SENT albo FAILED — klient w kartotece
 * ma zobaczyć „wysłano o 12:00", nie „w kolejce" na zawsze.
 *
 * Jedna partia to najwyżej [BATCH_SIZE] wiadomości na minutę; resztę zabierze
 * kolejny tick. To celowo prosty limit — chroni przed zalaniem SMSAPI po dłuższej
 * przerwie, a rano o 12:00 wyjdzie i tak wszystko z nocy w kilka minut.
 */
@Service
class OutboundMessageDispatcher(
    private val queue: OutboundMessageQueue,
    private val gateway: OutboundCommunicationGateway,
    private val communicationLogService: CommunicationLogService,
    private val sendWindow: SendWindow
) {
    private val logger = LoggerFactory.getLogger(OutboundMessageDispatcher::class.java)

    companion object {
        const val BATCH_SIZE = 200
    }

    @Scheduled(cron = "0 * * * * *")
    fun dispatch() {
        dispatch(Instant.now())
    }

    fun dispatch(now: Instant) {
        runCatching { failStale(now) }
            .onFailure { ex -> logger.error("Error sweeping stale outbound messages: {}", ex.message, ex) }

        if (!sendWindow.contains(now)) return

        val due = queue.dueIds(now, BATCH_SIZE)
        if (due.isEmpty()) return

        logger.info("Dispatching {} queued outbound message(s)", due.size)
        due.forEach { id ->
            runCatching { dispatchOne(id, now) }
                .onFailure { ex -> logger.error("Unexpected error dispatching queued message={}: {}", id, ex.message, ex) }
        }
    }

    private fun failStale(now: Instant) {
        queue.failStaleSending(now).forEach { id ->
            communicationLogService.recordQueuedOutcome(
                queuedMessageId = id,
                success = false,
                errorMessage = "Wysyłka przerwana (restart aplikacji w trakcie wysyłki)"
            )
        }
    }

    private fun dispatchOne(id: UUID, now: Instant) {
        val message = queue.claim(id, now) ?: return
        val entity = message.entity

        val outcome = try {
            gateway.deliverQueued(message)
        } catch (ex: Exception) {
            // Wyjątek spoza kontraktu bramki (np. baza padła w trakcie) — traktujemy jak błąd
            // dostawcy: ponowimy, a po wyczerpaniu prób wiersz zostanie FAILED z powodem.
            logger.error("Queued {} {} threw: {}", entity.channel, id, ex.message, ex)
            OutboundCommunicationGateway.QueuedDeliveryOutcome(
                success = false, externalMessageId = null, errorMessage = ex.message ?: ex.javaClass.simpleName, retryable = true
            )
        }

        val finishedAt = Instant.now()
        if (outcome.success) {
            queue.markSent(id, outcome.externalMessageId, finishedAt)
            communicationLogService.recordQueuedOutcome(id, success = true, errorMessage = null)
            logger.info(
                "Queued {} sent | id={} studio={} externalId={} context={}",
                entity.channel, id, entity.studioId, outcome.externalMessageId, entity.context
            )
            return
        }

        val nextAttemptAt = sendWindow.nextSlotFrom(finishedAt.plus(OutboundMessageQueue.RETRY_BACKOFF))
        val status = queue.markAttemptFailed(id, outcome.errorMessage, outcome.retryable, nextAttemptAt, finishedAt)
        if (status == OutboundMessageStatus.FAILED) {
            communicationLogService.recordQueuedOutcome(id, success = false, errorMessage = outcome.errorMessage)
        }
    }
}
