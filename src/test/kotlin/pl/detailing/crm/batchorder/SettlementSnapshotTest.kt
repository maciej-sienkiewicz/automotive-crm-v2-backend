package pl.detailing.crm.batchorder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.batchorder.report.SettlementSnapshot
import java.time.LocalDate
import java.util.UUID

/**
 * PDF z historii rozliczeń powstaje ze snapshotu, nie z żywych wpisów — dokument,
 * który kontrahent dostał, nie może się zmienić po korekcie wpisu.
 */
class SettlementSnapshotTest {

    private val contractorId = UUID.randomUUID()

    @Test
    fun `snapshot przenosi pozycje bez zmian, z brutto co do grosza`() {
        val entry = entry(contractorId, "2026-08-10").apply { notes = "Rysa na zderzaku" }

        val restored = SettlementSnapshot.fromJson(SettlementSnapshot.of(listOf(entry)).toJson())

        val row = restored.entries.single()
        assertEquals(entry.id.toString(), row.entryId)
        assertEquals(LocalDate.parse("2026-08-10"), row.serviceDate)
        assertEquals("Skoda", row.vehicleMake)
        assertEquals("Octavia", row.vehicleModel)
        assertEquals("WX 12345", row.vehicleLicensePlate)
        assertEquals("TMBJJ7NE0J0123456", row.vehicleVin)
        assertEquals("Rysa na zderzaku", row.notes)
        assertEquals("Powłoka ceramiczna", row.services.single().name)
        assertEquals(154_472L, row.netAmountCents)
        assertEquals(190_000L, row.grossAmountCents, "1900,00 zł wpisane brutto nie może wrócić jako 1900,01 zł")
        assertEquals(23, row.services.single().vatRate)
    }

    @Test
    fun `korekta wpisu po rozliczeniu nie zmienia snapshotu`() {
        val entry = entry(contractorId, "2026-08-10")
        val json = SettlementSnapshot.of(listOf(entry)).toJson()

        entry.services[0].grossAmountCents = 250_000
        entry.vehicleLicensePlate = "WX 99999"

        val row = SettlementSnapshot.fromJson(json).entries.single()
        assertEquals(190_000L, row.grossAmountCents)
        assertEquals("WX 12345", row.vehicleLicensePlate)
    }

    @Test
    fun `data jako tekst ISO, a nieznane pola nie psuja odczytu`() {
        val json = SettlementSnapshot.of(listOf(entry(contractorId, "2026-08-10"))).toJson()
        assertTrue(json.contains("\"serviceDate\":\"2026-08-10\""), json)

        val withFutureField = json.replaceFirst("{", "{\"futureField\":42,")
        assertEquals(1, SettlementSnapshot.fromJson(withFutureField).entries.size)
    }
}
