package pl.detailing.crm.ksef.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Baza trzyma stan pracy schedulera (IDLE | RUNNING | ERROR), API mówi o kompletności
 * listy (NEVER_SYNCED | RUNNING | SUCCESS | FAILED). Bez przekładu front dostawał „IDLE",
 * nie miał takiej wartości w typie i nad pełną listą faktur wyświetlał ostrzeżenie
 * „Nie pobrano jeszcze faktur z KSeF".
 */
class KsefSyncCursorApiStatusTest {

    private fun cursor() = KsefSyncCursorEntity(studioId = UUID.randomUUID())

    @Test
    fun `swiezy kursor to NEVER_SYNCED`() {
        assertEquals("NEVER_SYNCED", cursor().apiStatus())
    }

    @Test
    fun `po udanym przebiegu to SUCCESS, a nie IDLE`() {
        val synced = cursor().toSuccess(OffsetDateTime.now())
        assertEquals("IDLE", synced.syncStatus, "stan pracy schedulera zostaje bez zmian")
        assertEquals("SUCCESS", synced.apiStatus())
    }

    @Test
    fun `trwajacy przebieg to RUNNING`() {
        assertEquals("RUNNING", cursor().toRunning().apiStatus())
    }

    @Test
    fun `blad to FAILED, takze po wczesniejszym sukcesie`() {
        assertEquals("FAILED", cursor().toError("KSeF timeout").apiStatus())
        assertEquals(
            "FAILED",
            cursor().toSuccess(OffsetDateTime.now()).toError("KSeF timeout").apiStatus()
        )
    }

    /** Reset zawieszonego przebiegu nie kasuje wiedzy o tym, że dane już pobrano. */
    @Test
    fun `reset zawieszonego przebiegu wraca do SUCCESS`() {
        val revived = cursor().toSuccess(OffsetDateTime.now()).toRunning().toIdle()
        assertEquals("SUCCESS", revived.apiStatus())
    }
}
