package pl.detailing.crm.subscription.lifecycle

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Daty w komunikatach dla właściciela studia. Dawniej komunikaty wstawiały surowy `Instant`
 * („…okresu (2026-10-11T18:14:01.418760Z)") — czas UTC z nanosekundami w zdaniu po polsku.
 */
object BillingDates {
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.of("Europe/Warsaw"))

    fun format(instant: Instant): String = DATE.format(instant)
}
