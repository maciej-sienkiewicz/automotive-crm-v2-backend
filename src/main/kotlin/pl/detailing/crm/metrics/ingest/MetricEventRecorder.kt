package pl.detailing.crm.metrics.ingest

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.detailing.crm.metrics.config.MetricsProperties
import pl.detailing.crm.metrics.domain.ActorKind
import pl.detailing.crm.metrics.domain.MetricEventType
import pl.detailing.crm.metrics.domain.MetricsClock
import pl.detailing.crm.metrics.infrastructure.MetricEventEntity
import pl.detailing.crm.metrics.infrastructure.MetricEventRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * The single entry point business code uses to record a metric event.
 *
 * Three properties matter more than anything else here, and they are the reason this
 * class exists instead of a repository call at each call site:
 *
 * 1. **It never throws.** A metrics failure that rolls back a customer's reservation is
 *    strictly worse than losing the metric. Every path is wrapped; the worst outcome is
 *    a dropped event and a log line.
 * 2. **It never blocks.** The hot path does an offer() onto a bounded queue — no I/O, no
 *    transaction, no lock contention on the writer.
 * 3. **It degrades visibly.** When the queue is full events are dropped and counted, and
 *    the drop counter is exposed. Silent data loss in a metrics pipeline is how a company
 *    ends up making decisions on numbers that stopped being true two months ago.
 *
 * The queue is deliberately bounded rather than unbounded: an unbounded queue under a
 * write storm trades "some lost metrics" for "OutOfMemoryError in the CRM", which is not
 * a trade anyone would accept if asked out loud.
 */
@Component
class MetricEventRecorder(
    private val repository: MetricEventRepository,
    private val properties: MetricsProperties,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val queue = ArrayBlockingQueue<MetricEventEntity>(properties.ingest.queueCapacity)

    private val droppedCount = AtomicLong(0)
    private val acceptedCount = AtomicLong(0)
    private val persistedCount = AtomicLong(0)

    /**
     * Records one event. Safe to call from anywhere, including inside a transaction that
     * may later roll back — the event is written on a separate thread and outside that
     * transaction, so "the reservation failed but we counted it" cannot happen for
     * failures, and a rollback after a successful commit-time call cannot happen either
     * because callers record *after* the business write succeeds.
     */
    fun record(
        eventType: MetricEventType,
        studioId: StudioId?,
        userId: UserId? = null,
        actorKind: ActorKind? = null,
        quantity: Long = 1,
        occurredAt: Instant = Instant.now(),
        payload: Map<String, Any?>? = null
    ) {
        if (!properties.enabled) return

        try {
            val entity = MetricEventEntity(
                id = UUID.randomUUID(),
                studioId = studioId?.value,
                userId = userId?.value,
                actorKind = actorKind,
                eventType = eventType,
                quantity = quantity,
                occurredAt = occurredAt,
                eventDate = MetricsClock.dateOf(occurredAt),
                payload = payload?.let { serialize(it) }
            )

            if (queue.offer(entity)) {
                acceptedCount.incrementAndGet()
            } else {
                val dropped = droppedCount.incrementAndGet()
                // Log the first drop and then every thousandth: a full queue produces
                // thousands of events per second, and a log line per drop would turn a
                // metrics hiccup into a disk-space incident.
                if (dropped == 1L || dropped % 1000 == 0L) {
                    log.warn(
                        "Kolejka zdarzeń metryk pełna (pojemność={}), odrzucono {} zdarzeń",
                        properties.ingest.queueCapacity, dropped
                    )
                }
            }
        } catch (ex: Exception) {
            log.warn("Nie udało się zarejestrować zdarzenia metryki {}: {}", eventType, ex.message)
        }
    }

    /**
     * Drains the queue into Postgres in batches.
     *
     * `saveAll` on a batch of a few hundred rows is one round trip; the alternative —
     * a save per event on the caller's thread — would add a database write to the
     * latency of every reservation the CRM creates.
     */
    @Scheduled(fixedDelayString = "\${crm.metrics.ingest.flush-interval-ms:5000}", initialDelay = 15_000)
    fun flush() {
        if (!properties.enabled || queue.isEmpty()) return

        val batch = ArrayList<MetricEventEntity>(properties.ingest.batchSize)
        try {
            while (queue.isNotEmpty()) {
                batch.clear()
                queue.drainTo(batch, properties.ingest.batchSize)
                if (batch.isEmpty()) break

                repository.saveAll(batch)
                persistedCount.addAndGet(batch.size.toLong())
            }
        } catch (ex: Exception) {
            // The drained batch is lost. Re-queueing it would risk an infinite loop when
            // the failure is a permanently bad row; losing a handful of events is the
            // cheaper failure, and the counters below make the loss visible.
            log.error("Zapis partii {} zdarzeń metryk nie powiódł się: {}", batch.size, ex.message, ex)
            droppedCount.addAndGet(batch.size.toLong())
        }
    }

    /** Best-effort drain so a rolling restart does not lose the last few seconds. */
    @PreDestroy
    fun drainOnShutdown() {
        log.info("Opróżnianie kolejki metryk przed zamknięciem — {} zdarzeń w kolejce", queue.size)
        flush()
    }

    /** Exposed for the platform console's self-diagnostics: is the pipeline keeping up? */
    fun stats(): IngestStats = IngestStats(
        queued = queue.size,
        capacity = properties.ingest.queueCapacity,
        accepted = acceptedCount.get(),
        persisted = persistedCount.get(),
        dropped = droppedCount.get()
    )

    private fun serialize(payload: Map<String, Any?>): String? = try {
        objectMapper.writeValueAsString(payload)
    } catch (ex: Exception) {
        log.warn("Nie udało się zserializować payloadu metryki: {}", ex.message)
        null
    }
}

data class IngestStats(
    val queued: Int,
    val capacity: Int,
    val accepted: Long,
    val persisted: Long,
    val dropped: Long
) {
    val saturationPercent: Int get() = if (capacity == 0) 0 else (queued * 100 / capacity)
    val healthy: Boolean get() = dropped == 0L && saturationPercent < 80
}
