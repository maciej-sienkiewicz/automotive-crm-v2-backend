package pl.detailing.crm.livemetrics.ingest

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.detailing.crm.livemetrics.config.LiveMetricsProperties
import pl.detailing.crm.livemetrics.domain.BusinessEvent
import pl.detailing.crm.livemetrics.store.LiveMetricsStore
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Hot path ingestu: `offer()` na ograniczoną kolejkę (żadnego I/O na wątku żądania),
 * osobny wątek zbiera partie i zapisuje je jednym pipeline do Redisa.
 *
 * Trzy właściwości są nienegocjowalne:
 *  1. nigdy nie rzuca — [accept] łapie wszystko;
 *  2. nigdy nie blokuje — pełna kolejka odrzuca zdarzenie zamiast czekać;
 *  3. degraduje się widocznie — odrzucenia są liczone i wystawione w konsoli
 *     platformy (`pipeline.dropped`), bo ciche gubienie danych to sposób, w jaki
 *     firma podejmuje decyzje na liczbach, które przestały być prawdziwe.
 */
@Component
class BusinessEventIngestWorker(
    private val store: LiveMetricsStore,
    private val properties: LiveMetricsProperties
) {
    private val log = LoggerFactory.getLogger(BusinessEventIngestWorker::class.java)

    private val queue = LinkedBlockingQueue<BusinessEvent>(properties.ingest.queueCapacity)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    val accepted = AtomicLong()
    val written = AtomicLong()
    val dropped = AtomicLong()
    val failedBatches = AtomicLong()

    fun accept(event: BusinessEvent) {
        if (!properties.enabled) return
        try {
            if (queue.offer(event)) accepted.incrementAndGet() else {
                val n = dropped.incrementAndGet()
                if (n % 1000 == 1L) log.warn("[LIVE-METRICS] Ingest queue full — dropped {} events so far", n)
            }
        } catch (e: Exception) {
            dropped.incrementAndGet()
        }
    }

    fun queued(): Int = queue.size
    fun capacity(): Int = properties.ingest.queueCapacity

    @PostConstruct
    fun start() {
        if (!properties.enabled) {
            log.info("[LIVE-METRICS] disabled (crm.live-metrics.enabled=false)")
            return
        }
        running.set(true)
        thread = Thread(::loop, "live-metrics-ingest").apply { isDaemon = true; start() }
        log.info("[LIVE-METRICS] ingest worker started (capacity={}, batch={}, flush={}ms)",
            properties.ingest.queueCapacity, properties.ingest.batchSize, properties.ingest.flushIntervalMs)
    }

    @PreDestroy
    fun stop() {
        running.set(false)
        thread?.interrupt()
        // Ostatnia szansa na zapis tego, co zostało w kolejce.
        runCatching { flushOnce(drainAll()) }
    }

    private fun loop() {
        while (running.get()) {
            try {
                val first = queue.poll(properties.ingest.flushIntervalMs, TimeUnit.MILLISECONDS) ?: continue
                val batch = ArrayList<BusinessEvent>(properties.ingest.batchSize)
                batch += first
                queue.drainTo(batch, properties.ingest.batchSize - 1)
                flushOnce(batch)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (e: Exception) {
                log.error("[LIVE-METRICS] ingest loop error: {}", e.toString())
            }
        }
    }

    /** Widoczne dla testów: zapis jednej partii, z liczeniem sukcesów i porażek. */
    fun flushOnce(batch: List<BusinessEvent>) {
        if (batch.isEmpty()) return
        try {
            store.record(batch)
            written.addAndGet(batch.size.toLong())
        } catch (e: Exception) {
            failedBatches.incrementAndGet()
            dropped.addAndGet(batch.size.toLong())
            log.error("[LIVE-METRICS] failed to write batch of {}: {}", batch.size, e.toString())
        }
    }

    private fun drainAll(): List<BusinessEvent> {
        val rest = ArrayList<BusinessEvent>()
        queue.drainTo(rest)
        return rest
    }
}
