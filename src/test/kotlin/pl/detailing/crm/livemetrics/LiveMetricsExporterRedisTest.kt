package pl.detailing.crm.livemetrics

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
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
import java.time.Instant
import java.util.Optional

@Tag("redis")
class LiveMetricsExporterRedisTest {

    private val props = LiveMetricsProperties()
    private val factory = LettuceConnectionFactory("localhost", 6399).apply { afterPropertiesSet() }
    private val redis = StringRedisTemplate(factory).apply { afterPropertiesSet() }
    private val store = LiveMetricsStore(redis, ObjectMapper().registerModule(JavaTimeModule()), props)

    @Test
    fun `a recorded reservation reaches the prometheus scrape with the right value`() {
        redis.connectionFactory!!.connection.use { it.serverCommands().flushDb() }
        val tenant = StudioId.random()
        val studio = mockk<StudioEntity>()
        every { studio.id } returns tenant.value
        every { studio.name } returns "Studio Blask"
        val studios = mockk<StudioRepository>()
        every { studios.findAllById(any<Iterable<java.util.UUID>>()) } returns listOf(studio)
        every { studios.findById(tenant.value) } returns Optional.of(studio)

        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val worker = mockk<BusinessEventIngestWorker>(relaxed = true)
        val broadcaster = mockk<LiveMetricsBroadcaster>(relaxed = true)
        val exporter = LiveMetricsPrometheusExporter(registry, store, worker, broadcaster, studios, props)
        exporter.register()

        val now = Instant.now()
        val batch = listOf(
            BusinessEvent(tenant, BusinessEventType.RESERVATION_CREATED, occurredAt = now),
            BusinessEvent(tenant, BusinessEventType.ACTIVITY_LOGGED, occurredAt = now),
            BusinessEvent(tenant, BusinessEventType.ACTIVITY_LOGGED, occurredAt = now)
        )
        store.record(batch)
        exporter.count(batch)
        exporter.refreshTodayGauges()

        exporter.refreshHourProfileGauges()

        // Dokładnie to, co zobaczy Prometheus — łącznie z nazwami metryk po konwersji Micrometera.
        val scrape = registry.scrape()
        fun value(line: String): Double = scrape.lineSequence()
            .firstOrNull { it.startsWith(line) }
            ?.substringAfterLast(' ')?.toDouble()
            ?: fail("brak serii w scrape: $line\n$scrape")

        val t = tenant.value
        assertEquals(1.0, value("""crm_business_events_today{tenant="Studio Blask",tenant_id="$t",type="RESERVATION_CREATED",}"""))
        assertEquals(2.0, value("""crm_business_events_today{tenant="Studio Blask",tenant_id="$t",type="ACTIVITY_LOGGED",}"""))
        assertEquals(0.0, value("""crm_business_events_today{tenant="Studio Blask",tenant_id="$t",type="VISIT_CREATED",}"""))
        assertEquals(1.0, value("""crm_business_events_total{dimension="none",tenant="Studio Blask",tenant_id="$t",type="RESERVATION_CREATED",}"""))

        // Liczniki muszą być widoczne z zerem, zanim padnie pierwsze zdarzenie danej kombinacji.
        // Seria pojawiająca się od razu z jedynką jest dla increase() niewidzialna (nie ma od czego
        // odjąć pierwszej próbki), przez co wykresy gubiły pierwsze zdarzenie po każdym restarcie.
        assertEquals(0.0, value("""crm_business_events_total{dimension="DIRECT",tenant="Studio Blask",tenant_id="$t",type="VISIT_CREATED",}"""))
        assertEquals(0.0, value("""crm_business_events_total{dimension="FROM_RESERVATION",tenant="Studio Blask",tenant_id="$t",type="VISIT_CREATED",}"""))
        assertEquals(0.0, value("""crm_business_events_total{dimension="CHECKIN",tenant="Studio Blask",tenant_id="$t",type="PHOTO_UPLOADED",}"""))

        val hour = "%02d".format(now.atZone(store.zone).hour)
        assertEquals(1.0, value("""crm_business_events_hour_of_day{hour="$hour",tenant="Studio Blask",tenant_id="$t",type="RESERVATION_CREATED",}"""))
    }
}
