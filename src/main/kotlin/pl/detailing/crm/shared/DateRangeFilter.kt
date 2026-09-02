package pl.detailing.crm.shared

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * Converts a user-facing date range (`?dateFrom=2026-05-21&dateTo=2026-08-19`) into the
 * instant bounds a `timestamptz` column can be compared against.
 *
 * ## Why this exists in one place
 *
 * The UI filters by calendar day; several tables store an instant. Every screen that
 * bridges the two has to answer the same two questions, and both have a wrong answer that
 * looks right in testing:
 *
 * - **Which timezone?** Parsing `2026-05-21` as `2026-05-21T00:00:00Z` — the obvious
 *   shortcut — makes a Polish studio filtering "od 21 maja" silently miss everything
 *   invoiced between 00:00 and 02:00 local time that day. Nobody notices until an
 *   accountant reconciles a month and is short two invoices.
 * - **Is the end inclusive?** `dateTo` names a day the user expects to see *in full*, so
 *   the bound has to be the start of the following day, compared with `<`. The common
 *   `…T23:59:59` shortcut drops the final second of the range — rare, and therefore
 *   found in production rather than in a test.
 *
 * Both callers of `KsefInvoiceRepository.findWithFilters` had one of these wrong, in
 * different ways. Answering it once removes the chance of a third.
 */
object DateRangeFilter {

    /**
     * The business timezone every studio on this platform operates in. The live-metrics
     * module bucketing uses the same zone (configurable there); this constant is about
     * interpreting a query parameter.
     */
    val ZONE: ZoneId = ZoneId.of("Europe/Warsaw")

    /** Inclusive lower bound: local start of the given day. */
    fun startOfDay(date: LocalDate?): OffsetDateTime? =
        date?.atStartOfDay(ZONE)?.toOffsetDateTime()

    /**
     * **Exclusive** upper bound: local start of the day *after* the given one.
     * Compare with `<`, never `<=`, or the whole final day is counted twice over.
     */
    fun startOfNextDay(date: LocalDate?): OffsetDateTime? =
        date?.plusDays(1)?.atStartOfDay(ZONE)?.toOffsetDateTime()
}
