package pl.detailing.crm.metrics.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The one place that decides what "a day" means for this platform.
 *
 * Every studio using this CRM operates in Poland, and an owner asking "ile rezerwacji
 * zrobiliśmy wczoraj" means their local calendar day. Bucketing by UTC would move two
 * hours of every summer evening into the next day and quietly desynchronise the metrics
 * from the invoices, the calendar and the owner's own memory.
 *
 * If the platform ever sells outside this timezone, this object is the single seam where
 * a per-tenant zone gets introduced.
 */
object MetricsClock {

    val ZONE: ZoneId = ZoneId.of("Europe/Warsaw")

    fun dateOf(instant: Instant): LocalDate = instant.atZone(ZONE).toLocalDate()

    fun today(): LocalDate = LocalDate.now(ZONE)

    fun yesterday(): LocalDate = today().minusDays(1)

    /** Inclusive start-of-day instant for a local date. */
    fun startOf(date: LocalDate): Instant = date.atStartOfDay(ZONE).toInstant()

    /** Exclusive end-of-day instant — `startOf(date.plusDays(1))`. */
    fun endOf(date: LocalDate): Instant = startOf(date.plusDays(1))
}
