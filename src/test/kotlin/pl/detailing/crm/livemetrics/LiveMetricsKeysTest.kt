package pl.detailing.crm.livemetrics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.livemetrics.store.LiveMetricsKeys
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class LiveMetricsKeysTest {

    private val warsaw = ZoneId.of("Europe/Warsaw")

    @Test
    fun `buckets are computed in the studio zone not in UTC`() {
        // 2026-07-01T22:30Z is 2026-07-02 00:30 in Warsaw (CEST)
        val at = LiveMetricsKeys.atZone(Instant.parse("2026-07-01T22:30:00Z"), warsaw)
        assertEquals("20260702", LiveMetricsKeys.dayField(at))
        assertEquals("00", LiveMetricsKeys.hourField(at))
        assertEquals("0030", LiveMetricsKeys.minuteField(at))
    }

    @Test
    fun `key layout is stable and scoped`() {
        val tenant = UUID.fromString("11111111-2222-3333-4444-555555555555")
        val scope = LiveMetricsKeys.tenantScope(tenant)
        val day = LocalDate.of(2026, 9, 2)
        assertEquals("lm:t:11111111-2222-3333-4444-555555555555:VISIT_CREATED:DIRECT:m:20260902",
            LiveMetricsKeys.minuteHash(scope, "VISIT_CREATED:DIRECT", day))
        assertEquals("lm:p:RESERVATION_CREATED:h:20260902", LiveMetricsKeys.hourHash(LiveMetricsKeys.PLATFORM_SCOPE, "RESERVATION_CREATED", day))
        assertEquals("lm:p:RESERVATION_CREATED:d", LiveMetricsKeys.dayHash(LiveMetricsKeys.PLATFORM_SCOPE, "RESERVATION_CREATED"))
        assertEquals("lm:$scope:total", LiveMetricsKeys.totalHash(scope))
        assertEquals("lm:$scope:recent", LiveMetricsKeys.recentList(scope))
    }
}
