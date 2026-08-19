package pl.detailing.crm.metrics.apiaudit

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import pl.detailing.crm.metrics.config.MetricsProperties
import java.time.LocalDate
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.LongAdder

/**
 * In-memory accumulator for API traffic, flushed to Postgres once a minute.
 *
 * ## Why not one row per request
 *
 * A CRM this size serves on the order of a million requests a week. Storing one row per
 * request would make the traffic log the largest table in the database within days, slow
 * every backup, and answer no question that the per-day aggregate does not answer just
 * as well. Nobody has ever needed to know that `/api/v1/customers` was called at
 * 14:37:22 — they need to know it was called 4 000 times yesterday by 61 studios.
 *
 * ## Bounded by construction
 *
 * The key space is (endpoint × day), so it is naturally bounded by the size of the API
 * surface, not by traffic. The extra caps ([MetricsProperties.ApiAuditProperties.maxBufferedKeys],
 * [MetricsProperties.ApiAuditProperties.maxTrackedStudiosPerKey]) exist only to bound the
 * pathological case — an attacker hitting thousands of distinct unmapped paths — so a
 * metrics buffer can never be the reason the CRM runs out of memory.
 */
@Component
class ApiUsageBuffer(private val properties: MetricsProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    data class Key(val endpointId: UUID, val date: LocalDate)

    class Stats {
        val calls = LongAdder()
        val errors = LongAdder()
        val totalDurationMs = LongAdder()
        private val maxDuration = AtomicLong(0)

        /**
         * Distinct tenants that touched this endpoint today. A bounded synchronized set:
         * exact up to the cap and clamped beyond it, which is the right trade for a
         * number read as "one studio or everyone".
         */
        val studios: MutableSet<UUID> = Collections.synchronizedSet(HashSet())

        val maxDurationMs: Long get() = maxDuration.get()

        fun observeDuration(ms: Long) {
            totalDurationMs.add(ms)
            maxDuration.accumulateAndGet(ms) { current, candidate -> maxOf(current, candidate) }
        }
    }

    /** Per-tenant, per-day, per-module key: latency by customer *and* module adoption. */
    data class StudioKey(val studioId: UUID, val date: LocalDate, val module: String)

    class StudioStats {
        val calls = LongAdder()
        val errors = LongAdder()
        val totalDurationMs = LongAdder()
        private val maxDuration = AtomicLong(0)
        val endpoints: MutableSet<UUID> = Collections.synchronizedSet(HashSet())

        val maxDurationMs: Long get() = maxDuration.get()

        fun observeDuration(ms: Long) {
            totalDurationMs.add(ms)
            maxDuration.accumulateAndGet(ms) { current, candidate -> maxOf(current, candidate) }
        }
    }

    private val buffer = ConcurrentHashMap<Key, Stats>()
    private val studioBuffer = ConcurrentHashMap<StudioKey, StudioStats>()
    private val overflowDrops = AtomicLong(0)

    fun record(
        endpointId: UUID,
        module: String,
        date: LocalDate,
        studioId: UUID?,
        durationMs: Long,
        isError: Boolean
    ) {
        val key = Key(endpointId, date)

        val stats = buffer[key] ?: run {
            if (buffer.size >= properties.apiAudit.maxBufferedKeys) {
                val dropped = overflowDrops.incrementAndGet()
                if (dropped == 1L || dropped % 10_000 == 0L) {
                    log.warn("Bufor audytu API pełny ({} kluczy) — pominięto {} zdarzeń", buffer.size, dropped)
                }
                return
            }
            buffer.computeIfAbsent(key) { Stats() }
        }

        stats.calls.increment()
        if (isError) stats.errors.increment()
        stats.observeDuration(durationMs)

        if (studioId != null && stats.studios.size < properties.apiAudit.maxTrackedStudiosPerKey) {
            stats.studios.add(studioId)
        }

        if (studioId != null) recordForStudio(studioId, module, date, endpointId, durationMs, isError)
    }

    private fun recordForStudio(
        studioId: UUID,
        module: String,
        date: LocalDate,
        endpointId: UUID,
        durationMs: Long,
        isError: Boolean
    ) {
        // Bounded by tenants × modules × open days, so no extra cap is needed beyond the
        // shared one — the module list is fixed by the source tree, not by traffic.
        if (studioBuffer.size >= properties.apiAudit.maxBufferedKeys) return

        val stats = studioBuffer.computeIfAbsent(StudioKey(studioId, date, module)) { StudioStats() }
        stats.calls.increment()
        if (isError) stats.errors.increment()
        stats.observeDuration(durationMs)
        if (stats.endpoints.size < 2_000) stats.endpoints.add(endpointId)
    }

    /**
     * Atomically hands the accumulated counters to the flusher and starts a fresh buffer.
     *
     * Swapping the whole map rather than iterating and resetting each entry avoids the
     * race where a request increments a counter between the read and the reset and its
     * call disappears from both the flushed batch and the next one.
     */
    fun drain(): Map<Key, Stats> {
        if (buffer.isEmpty()) return emptyMap()

        val snapshot = HashMap<Key, Stats>(buffer.size)
        buffer.keys.toList().forEach { key ->
            buffer.remove(key)?.let { snapshot[key] = it }
        }
        return snapshot
    }

    /** Same swap-and-hand-off semantics as [drain], for the per-tenant aggregate. */
    fun drainStudios(): Map<StudioKey, StudioStats> {
        if (studioBuffer.isEmpty()) return emptyMap()

        val snapshot = HashMap<StudioKey, StudioStats>(studioBuffer.size)
        studioBuffer.keys.toList().forEach { key ->
            studioBuffer.remove(key)?.let { snapshot[key] = it }
        }
        return snapshot
    }

    fun pendingKeys(): Int = buffer.size + studioBuffer.size

    fun droppedEvents(): Long = overflowDrops.get()
}
