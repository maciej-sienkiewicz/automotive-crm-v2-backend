package pl.detailing.crm.livemetrics.stream

import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.stream.StreamListener
import org.springframework.stereotype.Component
import pl.detailing.crm.livemetrics.api.BusinessEventDto
import pl.detailing.crm.livemetrics.domain.BusinessEventType
import pl.detailing.crm.livemetrics.store.LiveMetricsKeys
import java.time.Instant
import java.util.UUID

/**
 * Czyta `lm:events` (Redis Streams) od momentu startu instancji i przekazuje każde
 * zdarzenie do [LiveMetricsBroadcaster]. Bez grupy konsumentów — celowo: każda
 * instancja ma własnych subskrybentów WebSocket/SSE i musi zobaczyć wszystko.
 */
@Component
class BusinessEventStreamConsumer(
    private val broadcaster: LiveMetricsBroadcaster
) : StreamListener<String, MapRecord<String, String, String>> {

    private val log = LoggerFactory.getLogger(BusinessEventStreamConsumer::class.java)

    override fun onMessage(message: MapRecord<String, String, String>) {
        val dto = try {
            toDto(message.value)
        } catch (e: Exception) {
            log.warn("[LIVE-METRICS] Skipping malformed stream record {}: {}", message.id, e.toString())
            return
        } ?: return
        broadcaster.publish(dto)
    }

    private fun toDto(fields: Map<String, String>): BusinessEventDto? {
        val type = fields["type"]?.let { runCatching { BusinessEventType.valueOf(it) }.getOrNull() } ?: return null
        val tenantId = fields["tenantId"]?.let { UUID.fromString(it) } ?: return null
        val dim = fields["dim"]
        val series = if (dim == null) listOf(type.series) else listOf(type.series, type.subSeries(dim))
        return BusinessEventDto(
            id = fields["id"]?.let { UUID.fromString(it) } ?: UUID.randomUUID(),
            tenantId = tenantId,
            type = type,
            series = series,
            dimension = type.dimension,
            dimensionValue = dim,
            occurredAt = fields["at"]?.toLongOrNull()?.let { Instant.ofEpochMilli(it) } ?: Instant.now(),
            attributes = fields.filterKeys { it.startsWith("a:") }.mapKeys { it.key.removePrefix("a:") }
        )
    }

    companion object {
        const val STREAM = LiveMetricsKeys.EVENTS_STREAM
    }
}
