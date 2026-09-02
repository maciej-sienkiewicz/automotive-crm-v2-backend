package pl.detailing.crm.livemetrics

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.livemetrics.config.LiveMetricsProperties
import pl.detailing.crm.livemetrics.domain.BusinessEvent
import pl.detailing.crm.livemetrics.domain.BusinessEventType
import pl.detailing.crm.livemetrics.ingest.BusinessEventIngestWorker
import pl.detailing.crm.livemetrics.store.LiveMetricsStore
import pl.detailing.crm.shared.StudioId

class BusinessEventIngestWorkerTest {

    private val tenant = StudioId.random()
    private fun event() = BusinessEvent(tenant, BusinessEventType.RESERVATION_CREATED)

    @Test
    fun `full queue drops instead of blocking and counts the drop`() {
        val store = mockk<LiveMetricsStore>(relaxed = true)
        val worker = BusinessEventIngestWorker(store, LiveMetricsProperties(ingest = LiveMetricsProperties.Ingest(queueCapacity = 2)))
        // worker thread not started on purpose: nothing drains the queue
        repeat(5) { worker.accept(event()) }
        assertEquals(2, worker.queued())
        assertEquals(2, worker.accepted.get())
        assertEquals(3, worker.dropped.get())
    }

    @Test
    fun `disabled module accepts nothing`() {
        val store = mockk<LiveMetricsStore>(relaxed = true)
        val worker = BusinessEventIngestWorker(store, LiveMetricsProperties(enabled = false))
        worker.accept(event())
        assertEquals(0, worker.queued())
        assertEquals(0, worker.accepted.get())
    }

    @Test
    fun `failed batch is counted as dropped and never propagates`() {
        val store = mockk<LiveMetricsStore>()
        every { store.record(any()) } throws RuntimeException("redis down")
        val worker = BusinessEventIngestWorker(store, LiveMetricsProperties())
        worker.flushOnce(listOf(event(), event()))
        assertEquals(1, worker.failedBatches.get())
        assertEquals(2, worker.dropped.get())
        assertEquals(0, worker.written.get())
    }

    @Test
    fun `successful batch is written once as a whole`() {
        val store = mockk<LiveMetricsStore>(relaxed = true)
        val worker = BusinessEventIngestWorker(store, LiveMetricsProperties())
        val batch = listOf(event(), event(), event())
        worker.flushOnce(batch)
        verify(exactly = 1) { store.record(batch) }
        assertEquals(3, worker.written.get())
    }
}
