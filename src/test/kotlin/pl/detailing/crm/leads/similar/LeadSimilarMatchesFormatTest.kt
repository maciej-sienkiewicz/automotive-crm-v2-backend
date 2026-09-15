package pl.detailing.crm.leads.similar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Wsteczna zgodność formatu zapisanego doboru.
 *
 * W bazie leżą wpisy 2-członowe (visitId;RANGA) sprzed przebudowy, w tym z rangami,
 * których enum już nie zna. Parser, który by je wycinał, zamieniłby stare wiersze
 * w CICHY dobór pusty — a o losie wiersza i tak rozstrzyga rules_version, nie format.
 */
class LeadSimilarMatchesFormatTest {

    private val visitA = UUID.randomUUID()
    private val visitB = UUID.randomUUID()

    private fun row(matches: String) = LeadSimilarMatchesEntity(
        leadId = UUID.randomUUID(),
        studioId = UUID.randomUUID(),
        matches = matches
    )

    @Test
    fun `wpis dwuczlonowy sprzed przebudowy czyta sie dalej`() {
        val row = row("$visitA;SAME_MODEL_SAME_SERVICE|$visitB;SAME_SEGMENT_SIMILAR_SERVICE")

        val parsed = row.parsedMatches()

        assertEquals(2, parsed.size)
        assertEquals(MatchTier.SAME_MODEL_SAME_SERVICE, parsed[0].tier)
        assertNull(parsed[0].compClass)
    }

    @Test
    fun `skasowana ranga daje tier null zamiast wyciecia wpisu`() {
        // SAME_MODEL_OTHER_SERVICE i MODEL_HISTORY zniknęły z enuma — wpis zostaje,
        // żeby lista nie skróciła się po cichu; przeliczenie i tak wymusi rules_version.
        val row = row("$visitA;SAME_MODEL_OTHER_SERVICE|$visitB;SAME_MODEL_SAME_SERVICE")

        val parsed = row.parsedMatches()

        assertEquals(2, parsed.size)
        assertNull(parsed[0].tier)
        assertEquals(MatchTier.SAME_MODEL_SAME_SERVICE, parsed[1].tier)
        // Zgodność wsteczna parsed(): pary tylko ze znaną rangą.
        assertEquals(listOf(visitB to MatchTier.SAME_MODEL_SAME_SERVICE), row.parsed())
    }

    @Test
    fun `format trzyczlonowy niesie klase compa i wraca w calosci`() {
        val serialized = LeadSimilarMatchesEntity.serializeMatches(
            listOf(
                LeadSimilarMatchesEntity.StoredMatch(visitA, MatchTier.SAME_MODEL_SAME_SERVICE, "DIRECT"),
                LeadSimilarMatchesEntity.StoredMatch(visitB, null, null)
            )
        )

        val parsed = row(serialized).parsedMatches()

        assertEquals(2, parsed.size)
        assertEquals("DIRECT", parsed[0].compClass)
        assertNull(parsed[1].tier)
        assertNull(parsed[1].compClass)
    }

    @Test
    fun `smieci w kolumnie nie wywracaja odczytu`() {
        val parsed = row("nie-uuid;COS|$visitA;SAME_MODEL_SAME_SERVICE|zepsuty-wpis").parsedMatches()
        assertEquals(1, parsed.size)
    }

    /** Świeżo zapisany wiersz JEST bieżący — a wiersz zastany z bazy (default kolumny 0) nie. */
    @Test
    fun `nowy wiersz dostaje biezaca wersje regul`() {
        assertTrue(row("").rulesVersion == LeadSimilarMatchesEntity.CURRENT_RULES_VERSION)
    }
}
