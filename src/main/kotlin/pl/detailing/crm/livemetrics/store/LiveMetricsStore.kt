package pl.detailing.crm.livemetrics.store

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.data.redis.connection.stream.MapRecord
import org.springframework.data.redis.core.RedisOperations
import org.springframework.data.redis.core.SessionCallback
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import pl.detailing.crm.livemetrics.api.BusinessEventDto
import pl.detailing.crm.livemetrics.config.LiveMetricsProperties
import pl.detailing.crm.livemetrics.domain.BusinessEvent
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Zapis i odczyt liczników kubełkowych oraz strumienia zdarzeń w Redisie.
 *
 * Zapis partii to jeden pipeline: dla każdego zdarzenia XADD do strumienia i po
 * kilka HINCRBY per seria × zakres. Odczyt składa serie z hashy dziennych,
 * dopełniając zerami — wykres na żywo musi mieć punkt dla każdej minuty, także tej,
 * w której nic się nie wydarzyło.
 */
@Component
class LiveMetricsStore(
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val properties: LiveMetricsProperties
) {
    private val log = LoggerFactory.getLogger(LiveMetricsStore::class.java)
    private val writesSinceTrim = AtomicLong()

    val zone: ZoneId get() = properties.zoneId

    // ── zapis ────────────────────────────────────────────────────────────────

    fun record(events: List<BusinessEvent>) {
        if (events.isEmpty()) return
        val minuteTtl = Duration.ofDays(properties.retention.minuteDays)
        val hourTtl = Duration.ofDays(properties.retention.hourDays)
        val recentKeep = properties.recentEvents.toLong()

        redis.executePipelined(object : SessionCallback<Any?> {
            @Suppress("UNCHECKED_CAST")
            override fun <K : Any, V : Any> execute(operations: RedisOperations<K, V>): Any? {
                val ops = operations as RedisOperations<String, String>
                val hash = ops.opsForHash<String, String>()
                for (event in events) {
                    val at = LiveMetricsKeys.atZone(event.occurredAt, zone)
                    val day = at.toLocalDate()
                    val json = objectMapper.writeValueAsString(BusinessEventDto.from(event))

                    ops.opsForStream<String, String>().add(MapRecord.create(LiveMetricsKeys.EVENTS_STREAM, streamFields(event)))
                    ops.opsForSet().add(LiveMetricsKeys.TENANTS, event.tenantId.value.toString())

                    for (scope in listOf(LiveMetricsKeys.tenantScope(event.tenantId.value), LiveMetricsKeys.PLATFORM_SCOPE)) {
                        for (series in event.series()) {
                            ops.opsForSet().add(LiveMetricsKeys.seriesSet(scope), series)
                            val mKey = LiveMetricsKeys.minuteHash(scope, series, day)
                            val hKey = LiveMetricsKeys.hourHash(scope, series, day)
                            hash.increment(mKey, LiveMetricsKeys.minuteField(at), 1)
                            ops.expire(mKey, minuteTtl)
                            hash.increment(hKey, LiveMetricsKeys.hourField(at), 1)
                            ops.expire(hKey, hourTtl)
                            hash.increment(LiveMetricsKeys.dayHash(scope, series), LiveMetricsKeys.dayField(at), 1)
                            hash.increment(LiveMetricsKeys.totalHash(scope), series, 1)
                            hash.put(LiveMetricsKeys.lastHash(scope), series, event.occurredAt.toEpochMilli().toString())
                        }
                        val recent = LiveMetricsKeys.recentList(scope)
                        ops.opsForList().leftPush(recent, json)
                        ops.opsForList().trim(recent, 0, recentKeep - 1)
                    }
                }
                return null
            }
        })

        // Przycinanie strumienia co ~1000 zapisów — MAXLEN w XADD nie jest dostępny w tej
        // wersji Spring Data Redis jako opcja szablonu, a osobny XTRIM raz na jakiś czas
        // daje ten sam efekt bez kosztu przy każdym zdarzeniu.
        if (writesSinceTrim.addAndGet(events.size.toLong()) >= 1000) {
            writesSinceTrim.set(0)
            runCatching { redis.opsForStream<String, String>().trim(LiveMetricsKeys.EVENTS_STREAM, properties.streamMaxLength, true) }
                .onFailure { log.warn("[LIVE-METRICS] XTRIM failed: {}", it.toString()) }
        }
    }

    private fun streamFields(event: BusinessEvent): Map<String, String> {
        val fields = LinkedHashMap<String, String>()
        fields["id"] = event.id.toString()
        fields["tenantId"] = event.tenantId.value.toString()
        fields["type"] = event.type.name
        fields["at"] = event.occurredAt.toEpochMilli().toString()
        event.dimensionValue?.let { fields["dim"] = it }
        event.attributes.forEach { (k, v) -> fields["a:$k"] = v }
        return fields
    }

    // ── odczyt ───────────────────────────────────────────────────────────────

    fun tenants(): Set<UUID> =
        (redis.opsForSet().members(LiveMetricsKeys.TENANTS) ?: emptySet())
            .mapNotNull { runCatching { UUID.fromString(it) }.getOrNull() }
            .toSet()

    fun knownSeries(scope: String): Set<String> =
        redis.opsForSet().members(LiveMetricsKeys.seriesSet(scope)) ?: emptySet()

    fun totals(scope: String): Map<String, Long> =
        redis.opsForHash<String, String>().entries(LiveMetricsKeys.totalHash(scope)).mapValues { it.value.toLongOrDefault() }

    fun lastSeen(scope: String): Map<String, Instant> =
        redis.opsForHash<String, String>().entries(LiveMetricsKeys.lastHash(scope))
            .mapNotNull { (k, v) -> v.toLongOrNull()?.let { k to Instant.ofEpochMilli(it) } }
            .toMap()

    fun recent(scope: String, limit: Int): List<BusinessEventDto> =
        (redis.opsForList().range(LiveMetricsKeys.recentList(scope), 0, (limit - 1).toLong()) ?: emptyList())
            .mapNotNull { runCatching { objectMapper.readValue(it, BusinessEventDto::class.java) }.getOrNull() }

    /** Punkty minutowe od [from] (zaokrąglone w dół do minuty) do [to] włącznie, z zerami. */
    fun minuteSeries(scope: String, series: String, from: Instant, to: Instant): List<Point> {
        val start = from.truncatedTo(ChronoUnit.MINUTES)
        val end = to.truncatedTo(ChronoUnit.MINUTES)
        val days = daysBetween(start, end)
        val buckets = readHashes(days.map { LiveMetricsKeys.minuteHash(scope, series, it) })
        val points = ArrayList<Point>()
        var cursor = start
        while (!cursor.isAfter(end)) {
            val at = cursor.atZone(zone)
            val count = buckets[LiveMetricsKeys.DAY.format(at)]?.get(LiveMetricsKeys.minuteField(at)) ?: 0L
            points += Point(cursor, count)
            cursor = cursor.plus(1, ChronoUnit.MINUTES)
        }
        return points
    }

    /** Punkty godzinowe (w strefie kubełków) od godziny zawierającej [from] do godziny zawierającej [to]. */
    fun hourSeries(scope: String, series: String, from: Instant, to: Instant): List<Point> {
        val start = from.atZone(zone).truncatedTo(ChronoUnit.HOURS)
        val end = to.atZone(zone).truncatedTo(ChronoUnit.HOURS)
        val days = daysBetween(start.toInstant(), end.toInstant())
        val buckets = readHashes(days.map { LiveMetricsKeys.hourHash(scope, series, it) })
        val points = ArrayList<Point>()
        var cursor = start
        while (!cursor.isAfter(end)) {
            val count = buckets[LiveMetricsKeys.DAY.format(cursor)]?.get(LiveMetricsKeys.hourField(cursor)) ?: 0L
            points += Point(cursor.toInstant(), count)
            cursor = cursor.plusHours(1)
        }
        return points
    }

    /** Punkty dzienne od [fromDay] do [toDay] włącznie (północ w strefie kubełków). */
    fun daySeries(scope: String, series: String, fromDay: LocalDate, toDay: LocalDate): List<Point> {
        val all = redis.opsForHash<String, String>().entries(LiveMetricsKeys.dayHash(scope, series))
        val points = ArrayList<Point>()
        var day = fromDay
        while (!day.isAfter(toDay)) {
            val count = all[LiveMetricsKeys.DAY.format(day)]?.toLongOrDefault() ?: 0L
            points += Point(day.atStartOfDay(zone).toInstant(), count)
            day = day.plusDays(1)
        }
        return points
    }

    /** Suma zdarzeń w każdej z 24 godzin doby (strefa kubełków) z ostatnich [days] dni. */
    fun hourOfDayProfile(scope: String, series: String, days: Int): LongArray {
        val today = LocalDate.now(zone)
        val keys = (0 until days).map { LiveMetricsKeys.hourHash(scope, series, today.minusDays(it.toLong())) }
        val profile = LongArray(24)
        readHashes(keys).values.forEach { hash ->
            hash.forEach { (hour, count) -> hour.toIntOrNull()?.takeIf { it in 0..23 }?.let { profile[it] += count } }
        }
        return profile
    }

    /** Wartość jednego pola dziennego dla wielu zakresów naraz (konsola platformy: „dziś per tenant”). */
    fun dayCounts(scopes: List<String>, series: List<String>, day: LocalDate): Map<String, Map<String, Long>> {
        if (scopes.isEmpty() || series.isEmpty()) return emptyMap()
        val field = LiveMetricsKeys.DAY.format(day)
        val results = redis.executePipelined(object : SessionCallback<Any?> {
            @Suppress("UNCHECKED_CAST")
            override fun <K : Any, V : Any> execute(operations: RedisOperations<K, V>): Any? {
                val ops = operations as RedisOperations<String, String>
                for (scope in scopes) for (s in series) ops.opsForHash<String, String>().get(LiveMetricsKeys.dayHash(scope, s), field)
                return null
            }
        })
        var i = 0
        val out = LinkedHashMap<String, MutableMap<String, Long>>()
        for (scope in scopes) {
            val perSeries = LinkedHashMap<String, Long>()
            for (s in series) {
                perSeries[s] = (results.getOrNull(i) as? String)?.toLongOrDefault() ?: 0L
                i++
            }
            out[scope] = perSeries
        }
        return out
    }

    private fun readHashes(keys: List<String>): Map<String, Map<String, Long>> {
        if (keys.isEmpty()) return emptyMap()
        val results = redis.executePipelined(object : SessionCallback<Any?> {
            @Suppress("UNCHECKED_CAST")
            override fun <K : Any, V : Any> execute(operations: RedisOperations<K, V>): Any? {
                val ops = operations as RedisOperations<String, String>
                keys.forEach { ops.opsForHash<String, String>().entries(it) }
                return null
            }
        })
        val out = HashMap<String, Map<String, Long>>()
        keys.forEachIndexed { idx, key ->
            @Suppress("UNCHECKED_CAST")
            val entries = results.getOrNull(idx) as? Map<String, String> ?: emptyMap()
            out[key.substringAfterLast(':')] = entries.mapValues { it.value.toLongOrDefault() }
        }
        return out
    }

    private fun daysBetween(from: Instant, to: Instant): List<LocalDate> {
        val first = from.atZone(zone).toLocalDate()
        val last = to.atZone(zone).toLocalDate()
        val days = ArrayList<LocalDate>()
        var d = first
        while (!d.isAfter(last)) { days += d; d = d.plusDays(1) }
        return days
    }

    private fun String.toLongOrDefault(): Long = toLongOrNull() ?: 0L

    data class Point(val at: Instant, val count: Long)
}
