package pl.detailing.crm.livemetrics.stream

import org.slf4j.LoggerFactory
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import pl.detailing.crm.livemetrics.api.BusinessEventDto
import pl.detailing.crm.livemetrics.api.LiveMetricsFrame
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicLong

/**
 * Fan-out zdarzenia do subskrybentów tej instancji:
 *
 *  - STOMP `/topic/studio.{tenantId}.metrics` — dashboard studia w SPA (istniejący
 *    kanał `/ws-registry`; `WebSocketSecurityInterceptor` dopuszcza subskrypcję
 *    wyłącznie własnego studia);
 *  - SSE dla studia (`/api/v1/live-metrics/stream`, sesja użytkownika);
 *  - SSE dla platformy (`/api/internal/live-metrics/stream`, `X-Platform-Key`) —
 *    wszystkie tenanty.
 *
 * Źródłem jest strumień Redis (`BusinessEventStreamConsumer`), nie lokalny ingest —
 * dzięki temu każda instancja aplikacji widzi zdarzenia zapisane przez dowolną inną.
 */
@Component
class LiveMetricsBroadcaster(
    private val messagingTemplate: SimpMessagingTemplate
) {
    private val log = LoggerFactory.getLogger(LiveMetricsBroadcaster::class.java)

    private val platformEmitters = CopyOnWriteArraySet<SseEmitter>()
    private val tenantEmitters = ConcurrentHashMap<UUID, CopyOnWriteArraySet<SseEmitter>>()
    val broadcast = AtomicLong()

    companion object {
        const val SSE_TIMEOUT_MS = 30L * 60 * 1000
        fun topic(tenantId: UUID) = "/topic/studio.$tenantId.metrics"
    }

    fun publish(event: BusinessEventDto) {
        val frame = LiveMetricsFrame(kind = LiveMetricsFrame.KIND_EVENT, event = event, timestamp = event.occurredAt)
        broadcast.incrementAndGet()
        try {
            messagingTemplate.convertAndSend(topic(event.tenantId), frame)
        } catch (e: Exception) {
            log.warn("[LIVE-METRICS] STOMP send failed for tenant {}: {}", event.tenantId, e.toString())
        }
        tenantEmitters[event.tenantId]?.let { sendAll(it, frame) }
        sendAll(platformEmitters, frame)
    }

    fun subscribePlatform(): SseEmitter = register(platformEmitters)

    fun subscribeTenant(tenantId: UUID): SseEmitter =
        register(tenantEmitters.computeIfAbsent(tenantId) { CopyOnWriteArraySet() })

    fun sseSubscribers(): Int = platformEmitters.size + tenantEmitters.values.sumOf { it.size }

    /** Utrzymuje połączenia SSE przy życiu za proxy, które tną bezczynne odpowiedzi. */
    @Scheduled(fixedDelay = 15_000)
    fun heartbeat() {
        val frame = LiveMetricsFrame(kind = LiveMetricsFrame.KIND_HEARTBEAT)
        sendAll(platformEmitters, frame)
        tenantEmitters.values.forEach { sendAll(it, frame) }
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
