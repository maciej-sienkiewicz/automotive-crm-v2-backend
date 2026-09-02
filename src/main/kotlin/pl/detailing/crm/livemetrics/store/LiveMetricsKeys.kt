package pl.detailing.crm.livemetrics.store

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Układ kluczy w Redisie. Wszystko pod prefiksem `lm:`.
 *
 * Zakres (scope) to `t:{tenantId}` dla studia albo `p` dla całej platformy — każde
 * zdarzenie inkrementuje oba, żeby konsola operatora nie musiała sumować setek
 * tenantów przy każdym odświeżeniu.
 *
 * ```
 * lm:events                          strumień zdarzeń (transport między instancjami)
 * lm:tenants                         zbiór tenantów, którzy mieli jakiekolwiek zdarzenie
 * lm:{scope}:series                  zbiór serii, które miały zdarzenie w tym zakresie
 * lm:{scope}:{series}:m:{yyyyMMdd}   hash  HHmm     -> licznik (TTL: retention.minute-days)
 * lm:{scope}:{series}:h:{yyyyMMdd}   hash  HH       -> licznik (TTL: retention.hour-days)
 * lm:{scope}:{series}:d              hash  yyyyMMdd -> licznik (bez TTL)
 * lm:{scope}:total                   hash  series   -> licznik od początku
 * lm:{scope}:last                    hash  series   -> epoch millis ostatniego zdarzenia
 * lm:{scope}:recent                  lista JSON ostatnich zdarzeń (LPUSH + LTRIM)
 * ```
 */
object LiveMetricsKeys {
    const val PREFIX = "lm"
    const val EVENTS_STREAM = "$PREFIX:events"
    const val TENANTS = "$PREFIX:tenants"
    const val PLATFORM_SCOPE = "p"

    fun tenantScope(tenantId: UUID): String = "t:$tenantId"

    fun seriesSet(scope: String) = "$PREFIX:$scope:series"
    fun minuteHash(scope: String, series: String, day: LocalDate) = "$PREFIX:$scope:$series:m:${DAY.format(day)}"
    fun hourHash(scope: String, series: String, day: LocalDate) = "$PREFIX:$scope:$series:h:${DAY.format(day)}"
    fun dayHash(scope: String, series: String) = "$PREFIX:$scope:$series:d"
    fun totalHash(scope: String) = "$PREFIX:$scope:total"
    fun lastHash(scope: String) = "$PREFIX:$scope:last"
    fun recentList(scope: String) = "$PREFIX:$scope:recent"

    val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    val MINUTE_FIELD: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmm")
    val HOUR_FIELD: DateTimeFormatter = DateTimeFormatter.ofPattern("HH")

    fun minuteField(at: ZonedDateTime): String = MINUTE_FIELD.format(at)
    fun hourField(at: ZonedDateTime): String = HOUR_FIELD.format(at)
    fun dayField(at: ZonedDateTime): String = DAY.format(at)

    fun atZone(instant: Instant, zone: ZoneId): ZonedDateTime = instant.atZone(zone)
}
