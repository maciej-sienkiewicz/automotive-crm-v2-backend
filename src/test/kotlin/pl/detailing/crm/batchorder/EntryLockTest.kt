package pl.detailing.crm.batchorder

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.batchorder.entry.LockedEntryAction
import pl.detailing.crm.batchorder.entry.ensureEntryEditable
import pl.detailing.crm.shared.ConflictException
import java.time.Instant
import java.util.UUID

/**
 * Regresja: zapis rozliczonego wpisu zdejmował mu `isClosed`, więc poprawka literówki
 * cofała rozliczenie i ta sama praca trafiała na następne zestawienie drugi raz.
 */
class EntryLockTest {

    private val contractorId = UUID.randomUUID()
    private val now = Instant.parse("2026-09-25T12:00:00Z")

    @Test
    fun `rozliczonego wpisu nie da sie zmienic ani usunac`() {
        val update = assertThrows<ConflictException> { ensureEntryEditable(isClosed = true, LockedEntryAction.UPDATE) }
        assertEquals("Wpis jest rozliczony. Odblokuj go do korekty, zanim go zmienisz.", update.message)

        val delete = assertThrows<ConflictException> { ensureEntryEditable(isClosed = true, LockedEntryAction.DELETE) }
        assertEquals("Wpis jest rozliczony. Odblokuj go do korekty, zanim go usuniesz.", delete.message)
    }

    @Test
    fun `otwarty wpis wolno zmieniac i usuwac`() {
        assertDoesNotThrow { ensureEntryEditable(isClosed = false, LockedEntryAction.UPDATE) }
        assertDoesNotThrow { ensureEntryEditable(isClosed = false, LockedEntryAction.DELETE) }
    }

    @Test
    fun `odblokowanie robi z rozliczonego wpisu otwarta korekte`() {
        val entry = entry(contractorId, "2026-08-10", closed = true)

        assertTrue(entry.reopenForCorrection(now))

        assertFalse(entry.isClosed)
        assertTrue(entry.isCorrection)
        assertNull(entry.closeHistoryId)
        assertEquals(now, entry.updatedAt)
        // Po odblokowaniu zapis znów przechodzi.
        assertDoesNotThrow { ensureEntryEditable(entry.isClosed, LockedEntryAction.UPDATE) }
    }

    @Test
    fun `odblokowanie otwartego wpisu niczego nie rusza`() {
        val entry = entry(contractorId, "2026-08-10", closed = false)
        val updatedBefore = entry.updatedAt

        assertFalse(entry.reopenForCorrection(now))

        assertFalse(entry.isClosed)
        assertFalse(entry.isCorrection, "wpis, którego nikt nie rozliczył, nie jest korektą")
        assertEquals(updatedBefore, entry.updatedAt)
    }

    @Test
    fun `ponowne rozliczenie domyka korekte`() {
        val entry = entry(contractorId, "2026-08-10", closed = true)
        entry.reopenForCorrection(now)
        val historyId = UUID.randomUUID()

        entry.markSettled(historyId, now)

        assertTrue(entry.isClosed)
        assertFalse(entry.isCorrection)
        assertEquals(historyId, entry.closeHistoryId)
    }
}
