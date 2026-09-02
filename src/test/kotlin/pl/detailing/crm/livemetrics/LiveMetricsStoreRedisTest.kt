package pl.detailing.crm.livemetrics

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import pl.detailing.crm.livemetrics.config.LiveMetricsProperties
import pl.detailing.crm.livemetrics.domain.BusinessEvent
import pl.detailing.crm.livemetrics.domain.BusinessEventType
import pl.detailing.crm.livemetrics.store.LiveMetricsKeys
import pl.detailing.crm.livemetrics.store.LiveMetricsStore
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.time.LocalDate

@Tag("redis")
class LiveMetricsStoreRedisTest {

    private val props = LiveMetricsProperties()
    private val factory = LettuceConnectionFactory("localhost", 6399).apply { afterPropertiesSet() }
    private val redis = StringRedisTemplate(factory).apply { afterPropertiesSet() }
    private val mapper = ObjectMapper().registerModule(JavaTimeModule())
    private val store = LiveMetricsStore(redis, mapper, props)

    @Test
    fun `recorded events are readable back through every read path`() {
        redis.connectionFactory!!.connection.use { it.serverCommands().flushDb() }
        val tenant = StudioId.random()
        val scope = LiveMetricsKeys.tenantScope(tenant.value)
        val now = Instant.now()

        store.record(listOf(
            BusinessEvent(tenant, BusinessEventType.RESERVATION_CREATED, occurredAt = now),
            BusinessEvent(tenant, BusinessEventType.ACTIVITY_LOGGED, occurredAt = now),
            BusinessEvent(tenant, BusinessEventType.ACTIVITY_LOGGED, occurredAt = now)
        ))

        println("RAW day hash reservations = " + redis.opsForHash<String, String>().entries(LiveMetricsKeys.dayHash(scope, "RESERVATION_CREATED")))
        println("RAW day hash activity     = " + redis.opsForHash<String, String>().entries(LiveMetricsKeys.dayHash(scope, "ACTIVITY_LOGGED")))
        println("tenants set               = " + store.tenants())

        val series = BusinessEventType.entries.map { it.series }
        val today = store.dayCounts(listOf(scope), series, LocalDate.now(store.zone))
        println("dayCounts                 = $today")

        val minute = store.minuteSeries(scope, "RESERVATION_CREATED", now.minusSeconds(300), now)
        println("minuteSeries sum          = " + minute.sumOf { it.count })

        val hourProfile = store.hourOfDayProfile(scope, "RESERVATION_CREATED", 7)
        println("hourProfile sum           = " + hourProfile.sum())

        assertEquals(1L, today[scope]?.get("RESERVATION_CREATED"), "dayCounts reservations")
        assertEquals(2L, today[scope]?.get("ACTIVITY_LOGGED"), "dayCounts activity")
        assertEquals(1L, minute.sumOf { it.count }, "minuteSeries reservations")
        assertEquals(1L, hourProfile.sum(), "hourOfDayProfile reservations")
    }
}
