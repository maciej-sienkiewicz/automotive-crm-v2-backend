package pl.detailing.crm.livemetrics.stream

import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import pl.detailing.crm.livemetrics.api.BusinessEventDto
import pl.detailing.crm.livemetrics.api.LiveMetricsFrame
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

/**
 * Fan-out zdarzenia do subskrybentów tej instancji: SSE konsoli operatora platformy
 * (`/api/internal/live-metrics/stream`, `X-Platform-Key`) — wszystkie tenanty.
 *
 * Metryki na żywo są narzędziem operatora CRM-a, nie studia, więc kanałów per-tenant
 * (STOMP `/topic/studio.{id}.metrics` i SSE za sesją użytkownika) już nie ma — zniknęły
 * razem z zakładką „Na żywo" w SPA, która jako jedyna je czytała.
 *
 * Źródłem jest strumień Redis ([BusinessEventStreamConsumer]), nie lokalny ingest —
 * dzięki temu konsola widzi zdarzenia zapisane przez dowolną instancję aplikacji.
 */
@Component
class LiveMetricsBroadcaster {

    private val platformEmitters = CopyOnWriteArraySet<SseEmitter>()
    val broadcast = AtomicLong()

    companion object {
        const val SSE_TIMEOUT_MS = 30L * 60 * 1000
    }

    fun publish(event: BusinessEventDto) {
        val frame = LiveMetricsFrame(kind = LiveMetricsFrame.KIND_EVENT, event = event, timestamp = event.occurredAt)
        broadcast.incrementAndGet()
        sendAll(platformEmitters, frame)
    }

    fun subscribePlatform(): SseEmitter = register(platformEmitters)

    fun sseSubscribers(): Int = platformEmitters.size

    /** Utrzymuje połączenia SSE przy życiu za proxy, które tną bezczynne odpowiedzi. */
    @Scheduled(fixedDelay = 15_000)
    fun heartbeat() {
        sendAll(platformEmitters, LiveMetricsFrame(kind = LiveMetricsFrame.KIND_HEARTBEAT))
    }

    private fun register(set: MutableSet<SseEmitter>): SseEmitter {
        val emitter = SseEmitter(SSE_TIMEOUT_MS)
        set += emitter
        val remove = Runnable { set.remove(emitter) }
        emitter.onCompletion(remove)
        emitter.onTimeout { emitter.complete(); remove.run() }
        emitter.onError { remove.run() }
        runCatching { emitter.send(SseEmitter.event().name("hello").data(LiveMetricsFrame(kind = LiveMetricsFrame.KIND_HEARTBEAT))) }
        return emitter
    }

    private fun sendAll(set: MutableSet<SseEmitter>, frame: LiveMetricsFrame) {
        for (emitter in set) {
            try {
                emitter.send(SseEmitter.event().name(frame.kind.lowercase()).data(frame))
            } catch (e: Exception) {
                set.remove(emitter)
                runCatching { emitter.completeWithError(e) }
            }
        }
    }
}
