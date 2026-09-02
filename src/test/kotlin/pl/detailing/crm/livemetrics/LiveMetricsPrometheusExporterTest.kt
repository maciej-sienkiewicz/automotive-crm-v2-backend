package pl.detailing.crm.livemetrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.livemetrics.config.LiveMetricsProperties
import pl.detailing.crm.livemetrics.domain.BusinessEvent
import pl.detailing.crm.livemetrics.domain.BusinessEventType
import pl.detailing.crm.livemetrics.ingest.BusinessEventIngestWorker
import pl.detailing.crm.livemetrics.prometheus.LiveMetricsPrometheusExporter
import pl.detailing.crm.livemetrics.store.LiveMetricsStore
import pl.detailing.crm.livemetrics.stream.LiveMetricsBroadcaster
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.studio.infrastructure.StudioEntity
import pl.detailing.crm.studio.infrastructure.StudioRepository
import java.util.Optional

class LiveMetricsPrometheusExporterTest {

    @Test
    fun `events become a tenant-labelled counter with a closed dimension set`() {
        val registry = SimpleMeterRegistry()
        val tenant = StudioId.random()
        val studio = mockk<StudioEntity>()
        every { studio.id } returns tenant.value
        every { studio.name } returns "Studio Blask"
        val studios = mockk<StudioRepository>()
        every { studios.findById(tenant.value) } returns Optional.of(studio)

        val exporter = LiveMetricsPrometheusExporter(
            registry, mockk<LiveMetricsStore>(relaxed = true), mockk<BusinessEventIngestWorker>(relaxed = true),
            mockk<LiveMetricsBroadcaster>(relaxed = true), studios, LiveMetricsProperties()
        )
        exporter.register()
        exporter.count(listOf(
            BusinessEvent(tenant, BusinessEventType.VISIT_CREATED, dimensionValue = "DIRECT"),
            BusinessEvent(tenant, BusinessEventType.VISIT_CREATED, dimensionValue = "DIRECT"),
            BusinessEvent(tenant, BusinessEventType.RESERVATION_CREATED)
        ))

        val direct = registry.get(LiveMetricsPrometheusExporter.EVENTS)
            .tags("tenant_id", tenant.value.toString(), "tenant", "Studio Blask", "type", "VISIT_CREATED", "dimension", "DIRECT").counter()
        assertEquals(2.0, direct.count())
        val reservations = registry.get(LiveMetricsPrometheusExporter.EVENTS)
            .tags("type", "RESERVATION_CREATED", "dimension", LiveMetricsPrometheusExporter.NO_DIMENSION).counter()
        assertEquals(1.0, reservations.count())
        assertEquals(2, registry.find(LiveMetricsPrometheusExporter.EVENTS).counters().size)
    }
}
