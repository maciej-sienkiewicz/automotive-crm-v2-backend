package pl.detailing.crm.batchorder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.batchorder.contractor.EntryPeriod
import pl.detailing.crm.batchorder.contractor.EntryStatusFilter
import pl.detailing.crm.batchorder.contractor.EntryStatusFilter.ALL
import pl.detailing.crm.batchorder.contractor.EntryStatusFilter.OPEN
import pl.detailing.crm.batchorder.contractor.EntryStatusFilter.SETTLED
import pl.detailing.crm.batchorder.contractor.lastSettledAt
import pl.detailing.crm.batchorder.contractor.sortForList
import pl.detailing.crm.batchorder.contractor.summarize
import pl.detailing.crm.batchorder.infrastructure.ServiceItemEmbeddable
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class EntryListingTest {

    private val contractorId = UUID.randomUUID()

    // ── status ───────────────────────────────────────────────────────────────

    @Test
    fun `status ma pierwszenstwo przed includeSettled`() {
        assertEquals(SETTLED, EntryStatusFilter.forEntryList("SETTLED", includeSettled = true))
        assertEquals(OPEN, EntryStatusFilter.forEntryList("OPEN", includeSettled = true))
        assertEquals(ALL, EntryStatusFilter.forEntryList("ALL", includeSettled = false))
    }

    @Test
    fun `bez statusu lista zachowuje sie jak dawniej`() {
        assertEquals(OPEN, EntryStatusFilter.forEntryList(null, includeSettled = false))
        assertEquals(ALL, EntryStatusFilter.forEntryList(null, includeSettled = true))
        assertEquals(OPEN, EntryStatusFilter.forEntryList("  ", includeSettled = false))
    }

    @Test
    fun `raport bez statusu obejmuje wszystko`() {
        assertEquals(ALL, EntryStatusFilter.resolve(null, default = ALL))
        assertEquals(OPEN, EntryStatusFilter.resolve("open", default = ALL))
    }

    @Test
    fun `nieznany status to blad, nie cichy fallback`() {
        assertThrows<ValidationException> { EntryStatusFilter.resolve("CLOSED", default = OPEN) }
    }

    @Test
    fun `filtr dopasowuje wpisy po isClosed`() {
        assertTrue(OPEN.matches(isClosed = false)); assertFalse(OPEN.matches(isClosed = true))
        assertTrue(SETTLED.matches(isClosed = true)); assertFalse(SETTLED.matches(isClosed = false))
        assertTrue(ALL.matches(isClosed = true)); assertTrue(ALL.matches(isClosed = false))
    }

    // ── kolejność i sumy ─────────────────────────────────────────────────────

    @Test
    fun `najnowsza data na gorze, przy tej samej dacie ostatnio dopisany`() {
        val oldest = entry(contractorId, "2026-08-01")
        val sameDayEarlier = entry(contractorId, "2026-08-15", createdAt = "2026-08-15T08:00:00Z")
        val sameDayLater = entry(contractorId, "2026-08-15", createdAt = "2026-08-15T17:00:00Z")
        val newest = entry(contractorId, "2026-08-20")

        val sorted = sortForList(listOf(oldest, sameDayEarlier, newest, sameDayLater))

        assertEquals(listOf(newest.id, sameDayLater.id, sameDayEarlier.id, oldest.id), sorted.map { it.id })
    }

    @Test
    fun `suma brutto to suma zapisanych brutto, a nie przeliczenie z netta`() {
        val entries = listOf(
            entry(contractorId, "2026-08-01"),
            entry(contractorId, "2026-08-02", services = listOf(
                grossTypedService(),
                ServiceItemEmbeddable("Mycie", netAmountCents = 10_000, grossAmountCents = 12_300, vatRate = 23)
            ))
        )

        val summary = summarize(entries)

        assertEquals(3, entries.sumOf { it.services.size })
        assertEquals(154_472L * 2 + 10_000, summary.totalNetCents)
        // 3 × 154472 × 1,23 dałoby grosze więcej; brutto wpisane to dokładnie 1900,00 zł na pozycję.
        assertEquals(190_000L * 2 + 12_300, summary.totalGrossCents)
        assertEquals(2, summary.entryCount)
    }

    // ── ostatnie rozliczenie ─────────────────────────────────────────────────

    @Test
    fun `ostatnie rozliczenie to najnowsze zachodzace na okres`() {
        val stamps = listOf(
            stamp(contractorId, "2026-07-01", "2026-07-31", "2026-08-02T09:00:00Z"),
            stamp(contractorId, "2026-08-01", "2026-08-15", "2026-08-16T09:00:00Z"),
            stamp(contractorId, "2026-08-16", "2026-08-31", "2026-09-01T09:00:00Z"),
            stamp(contractorId, "2026-09-01", "2026-09-30", "2026-10-01T09:00:00Z")
        )
        val august = EntryPeriod(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"))

        assertEquals(Instant.parse("2026-09-01T09:00:00Z"), lastSettledAt(stamps, august))
        // Bez okresu — najnowsze w ogóle, niezależnie od kolejności wejścia.
        assertEquals(Instant.parse("2026-10-01T09:00:00Z"), lastSettledAt(stamps.reversed(), null))
    }

    @Test
    fun `okres stykajacy sie jednym dniem tez sie liczy`() {
        val stamps = listOf(stamp(contractorId, "2026-07-01", "2026-08-01", "2026-08-02T09:00:00Z"))
        val august = EntryPeriod(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"))
        val september = EntryPeriod(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"))

        assertEquals(Instant.parse("2026-08-02T09:00:00Z"), lastSettledAt(stamps, august))
        assertNull(lastSettledAt(stamps, september))
    }

    @Test
    fun `okres wymaga obu koncow`() {
        assertNull(EntryPeriod.of(LocalDate.parse("2026-08-01"), null))
        assertNull(EntryPeriod.of(null, LocalDate.parse("2026-08-31")))
    }
}
