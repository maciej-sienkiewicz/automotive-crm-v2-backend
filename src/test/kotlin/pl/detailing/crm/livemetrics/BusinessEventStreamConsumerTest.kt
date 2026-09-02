package pl.detailing.crm.livemetrics

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.stream.MapRecord
import pl.detailing.crm.livemetrics.api.BusinessEventDto
import pl.detailing.crm.livemetrics.domain.BusinessEventType
import pl.detailing.crm.livemetrics.stream.BusinessEventStreamConsumer
import pl.detailing.crm.livemetrics.stream.LiveMetricsBroadcaster
import java.time.Instant
import java.util.UUID

class BusinessEventStreamConsumerTest {

    @Test
    fun `stream record is decoded into a dto with sub-series and attributes`() {
        val broadcaster = mockk<LiveMetricsBroadcaster>()
        val captured = slot<BusinessEventDto>()
        every { broadcaster.publish(capture(captured)) } answers { }
        val consumer = BusinessEventStreamConsumer(broadcaster)
        val tenant = UUID.randomUUID()
        val id = UUID.randomUUID()

        consumer.onMessage(MapRecord.create("lm:events", mapOf(
            "id" to id.toString(),
            "tenantId" to tenant.toString(),
            "type" to "PHOTO_UPLOADED",
            "dim" to "VEHICLE",
            "at" to "1750000000000",
            "a:vehicleId" to "v-1"
        )))

        val dto = captured.captured
        assertEquals(id, dto.id)
        assertEquals(tenant, dto.tenantId)
        assertEquals(BusinessEventType.PHOTO_UPLOADED, dto.type)
        assertEquals(listOf("PHOTO_UPLOADED", "PHOTO_UPLOADED:VEHICLE"), dto.series)
        assertEquals(Instant.ofEpochMilli(1750000000000), dto.occurredAt)
        assertEquals(mapOf("vehicleId" to "v-1"), dto.attributes)
    }

    @Test
    fun `malformed records are skipped without reaching subscribers`() {
        val broadcaster = mockk<LiveMetricsBroadcaster>(relaxed = true)
        val consumer = BusinessEventStreamConsumer(broadcaster)
        consumer.onMessage(MapRecord.create("lm:events", mapOf("type" to "NOT_A_TYPE", "tenantId" to UUID.randomUUID().toString())))
        consumer.onMessage(MapRecord.create("lm:events", mapOf("type" to "VISIT_CREATED", "tenantId" to "garbage")))
        verify(exactly = 0) { broadcaster.publish(any()) }
    }
}
