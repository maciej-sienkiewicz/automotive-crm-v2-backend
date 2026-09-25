package pl.detailing.crm.comms.draft

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ReplyExamplePairingTest {

    private val reply = "Dzień dobry Panie Marku,\n\ndziękujemy za wiadomość. Korekta lakieru w Pana aucie zajmie dwa dni robocze."

    @Test
    fun `stopka za separatorem nie trafia do przykladu`() {
        val prepared = ReplyExamplePairing.prepareReply("$reply\n\n-- \nAnna Nowak\ntel. 600 100 200")
        assertEquals(reply, prepared)
        assertFalse(prepared!!.contains("600 100 200"))
    }

    @Test
    fun `krotka odpowiedz nie uczy stylu`() {
        assertNull(ReplyExamplePairing.prepareReply("Dziękuję, do zobaczenia!"))
    }

    @Test
    fun `puste albo zdawkowe pytanie nie tworzy pary`() {
        assertNull(ReplyExamplePairing.prepareInquiry("   "))
        assertNull(ReplyExamplePairing.prepareInquiry("Ok"))
    }

    @Test
    fun `nadmiar pustych linii jest scisniety, dlugosc przycieta`() {
        val prepared = ReplyExamplePairing.prepareInquiry("Pytanie o powłokę\r\n\r\n\r\n\r\nw BMW " + "x".repeat(3000))!!
        assertFalse(prepared.contains("\n\n\n"))
        assertEquals(ReplyExamplePairing.MAX_INQUIRY_LENGTH, prepared.length)
    }

    @Test
    fun `wektor liczony z pytania z tematem`() {
        assertEquals("Temat: Wycena PPF\nIle za folię?", ReplyExamplePairing.embeddingInput(" Wycena PPF ", "Ile za folię?"))
        assertEquals("Ile za folię?", ReplyExamplePairing.embeddingInput(null, "Ile za folię?"))
    }

    @Test
    fun `szablon wyslany wielu klientom liczy sie jako jeden przyklad`() {
        fun example(text: String, distance: Double) = StoredReplyExample(
            UUID.randomUUID(), UUID.randomUUID(), null, "pytanie", text, Instant.EPOCH, distance
        )
        val nearest = example("Dzień dobry,  zapraszamy na oględziny.", 0.1)
        val examples = listOf(
            nearest,
            example("dzień dobry, zapraszamy na oględziny.", 0.2),
            example("Inna odpowiedź", 0.3)
        )
        val distinct = ReplyDraftService.distinctReplies(examples)
        assertEquals(2, distinct.size)
        assertEquals(nearest, distinct.first())
    }

    @Test
    fun `szkic bez bloku kodu i bez linii tematu`() {
        assertEquals(
            "Dzień dobry,\n\nzapraszamy.",
            ReplyDraftService.tidy("```\nTemat: Re: wycena\n\nDzień dobry,\n\n\n\nzapraszamy.\n```")
        )
    }
}
