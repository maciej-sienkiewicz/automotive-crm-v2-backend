package pl.detailing.crm.metrics.apiaudit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.metrics.config.MetricsProperties
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ApiUsageBufferTest {

    private val properties = MetricsProperties(
        apiAudit = MetricsProperties.ApiAuditProperties(
            maxBufferedKeys = 100,
            maxTrackedStudiosPerKey = 3
        )
    )

    private val date = LocalDate.of(2026, 8, 19)

    @Test
    fun `counters accumulate per endpoint and day`() {
        val buffer = ApiUsageBuffer(properties)
        val endpoint = UUID.randomUUID()
        val studio = UUID.randomUUID()

        buffer.record(endpoint, "visit", date, studio, durationMs = 10, isError = false)
        buffer.record(endpoint, "visit", date, studio, durationMs = 30, isError = true)

        val stats = buffer.drain().values.single()
        assertEquals(2, stats.calls.sum())
        assertEquals(1, stats.errors.sum())
        assertEquals(40, stats.totalDurationMs.sum())
        assertEquals(30, stats.maxDurationMs)
        assertEquals(1, stats.studios.size)
    }

    @Test
    fun `draining hands over the counters and starts fresh`() {
        val buffer = ApiUsageBuffer(properties)
        val endpoint = UUID.randomUUID()

        buffer.record(endpoint, "visit", date, UUID.randomUUID(), 5, false)
        assertEquals(1, buffer.drain().size)

        // The second drain must be empty: if drain() reset counters in place instead of
        // removing them, a flush would re-write the same numbers every minute and the
        // traffic totals would inflate without bound.
        assertTrue(buffer.drain().isEmpty())
    }

    @Test
    fun `distinct tenants are counted up to the configured cap`() {
        val buffer = ApiUsageBuffer(properties)
        val endpoint = UUID.randomUUID()

        repeat(10) { buffer.record(endpoint, "visit", date, UUID.randomUUID(), 1, false) }

        val stats = buffer.drain().values.single()
        assertEquals(10, stats.calls.sum(), "every call is still counted")
        assertEquals(3, stats.studios.size, "tenant set is capped, not the call counter")
    }

    @Test
    fun `the buffer refuses to grow past its cap instead of exhausting memory`() {
        val buffer = ApiUsageBuffer(properties)

        repeat(500) { buffer.record(UUID.randomUUID(), "visit", date, null, 1, false) }

        assertTrue(buffer.droppedEvents() > 0, "overflow must be visible, not silent")
        assertTrue(buffer.drain().size <= 100)
    }

    @Test
    fun `concurrent recording loses no calls`() {
        val buffer = ApiUsageBuffer(properties)
        val endpoint = UUID.randomUUID()
        val threads = 8
        val perThread = 500
        val pool = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(threads)

        repeat(threads) {
            pool.submit {
                repeat(perThread) { buffer.record(endpoint, "visit", date, null, 1, false) }
                latch.countDown()
            }
        }
        latch.await(10, TimeUnit.SECONDS)
        pool.shutdown()

        // LongAdder under contention: the whole point of this buffer is that it sits on
        // the hot path of every request, so a lost increment here is a lost request.
        assertEquals((threads * perThread).toLong(), buffer.drain().values.single().calls.sum())
    }

    @Test
    fun `per-tenant traffic is split by module`() {
        val buffer = ApiUsageBuffer(properties)
        val studio = UUID.randomUUID()

        buffer.record(UUID.randomUUID(), "visit", date, studio, 10, false)
        buffer.record(UUID.randomUUID(), "finance", date, studio, 20, false)
        buffer.record(UUID.randomUUID(), "finance", date, studio, 20, false)

        val byModule = buffer.drainStudios().mapKeys { it.key.module }

        assertEquals(setOf("visit", "finance"), byModule.keys)
        assertEquals(1, byModule.getValue("visit").calls.sum())
        assertEquals(2, byModule.getValue("finance").calls.sum())
    }

    @Test
    fun `unauthenticated traffic never lands in the per-tenant aggregate`() {
        val buffer = ApiUsageBuffer(properties)

        buffer.record(UUID.randomUUID(), "auth", date, studioId = null, durationMs = 5, isError = false)

        assertEquals(1, buffer.drain().size, "endpoint traffic is still recorded")
        assertTrue(buffer.drainStudios().isEmpty(), "no tenant means no tenant row")
    }
}
