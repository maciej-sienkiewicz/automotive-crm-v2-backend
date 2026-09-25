package pl.detailing.crm.batchorder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import pl.detailing.crm.batchorder.contractor.EntryPeriod
import pl.detailing.crm.batchorder.contractor.buildContractorsOverview
import pl.detailing.crm.batchorder.infrastructure.ServiceItemEmbeddable
import java.time.LocalDate
import java.util.UUID

class ContractorsOverviewTest {

    private val august = EntryPeriod(LocalDate.parse("2026-08-01"), LocalDate.parse("2026-08-31"))

    @Test
    fun `sumuje otwarte i liczy rozliczone per kontrahent`() {
        val autoKomis = contractor("Auto-Komis")
        val flota = contractor("Flota")

        val overview = buildContractorsOverview(
            contractors = listOf(autoKomis, flota),
            entries = listOf(
                entry(autoKomis.id, "2026-08-03"),
                entry(autoKomis.id, "2026-08-04"),
                entry(autoKomis.id, "2026-08-05", closed = true),
                entry(flota.id, "2026-08-06", closed = true)
            ),
            stamps = listOf(stamp(flota.id, "2026-08-01", "2026-08-31", "2026-09-01T08:00:00Z")),
            entryCounts = mapOf(autoKomis.id to 40L, flota.id to 7L),
            period = august
        )

        val first = overview[0]
        assertEquals("Auto-Komis", first.contractor.name)
        assertEquals(2, first.openCount)
        assertEquals(2 * 154_472L, first.openNetCents)
        assertEquals(2 * 190_000L, first.openGrossCents)
        assertEquals(1, first.settledCount)
        assertNull(first.lastSettledAt)
        assertEquals(40L, first.contractor.entryCount, "entryCount jak w liście kontrahentów — od zawsze")

        val second = overview[1]
        assertEquals("Flota", second.contractor.name)
        assertEquals(0, second.openCount)
        assertEquals(0L, second.openGrossCents)
        assertEquals(1, second.settledCount)
        assertEquals("2026-09-01T08:00:00Z", second.lastSettledAt)
        assertEquals(7L, second.contractor.entryCount)
    }

    @Test
    fun `kolejnosc - najwiecej brutto do rozliczenia, potem alfabetycznie po polsku`() {
        val lodz = contractor("Łódź Serwis")
        val zeta = contractor("Zeta")
        val mazur = contractor("Mazur Auto")
        val big = contractor("Duży Klient")

        val overview = buildContractorsOverview(
            contractors = listOf(zeta, mazur, lodz, big),
            entries = listOf(
                entry(big.id, "2026-08-10", services = listOf(
                    ServiceItemEmbeddable("Korekta lakieru", 400_000, 492_000, 23)
                )),
                entry(zeta.id, "2026-08-11")
            ),
            stamps = emptyList(),
            entryCounts = emptyMap(),
            period = august
        )

        // Ł po polsku stoi przed M, a nie za Z, jak w porządku kodów znaków.
        assertEquals(listOf("Duży Klient", "Zeta", "Łódź Serwis", "Mazur Auto"), overview.map { it.contractor.name })
        assertEquals(0L, overview[2].contractor.entryCount)
    }

    @Test
    fun `wpisy kontrahenta spoza listy sa pomijane`() {
        val active = contractor("Aktywny")
        val inactiveId = UUID.randomUUID()

        val overview = buildContractorsOverview(
            contractors = listOf(active),
            entries = listOf(entry(inactiveId, "2026-08-10")),
            stamps = listOf(stamp(inactiveId, "2026-08-01", "2026-08-31", "2026-09-01T08:00:00Z")),
            entryCounts = mapOf(inactiveId to 3L),
            period = august
        )

        assertEquals(1, overview.size)
        assertEquals(0, overview[0].openCount)
        assertNull(overview[0].lastSettledAt)
    }

    @Test
    fun `bez okresu ostatnie rozliczenie to najnowsze w ogole`() {
        val c = contractor("Kontrahent")

        val overview = buildContractorsOverview(
            contractors = listOf(c),
            entries = emptyList(),
            stamps = listOf(
                stamp(c.id, "2026-06-01", "2026-06-30", "2026-07-01T08:00:00Z"),
                stamp(c.id, "2026-07-01", "2026-07-31", "2026-08-01T08:00:00Z")
            ),
            entryCounts = emptyMap(),
            period = null
        )

        assertEquals("2026-08-01T08:00:00Z", overview[0].lastSettledAt)
    }
}
