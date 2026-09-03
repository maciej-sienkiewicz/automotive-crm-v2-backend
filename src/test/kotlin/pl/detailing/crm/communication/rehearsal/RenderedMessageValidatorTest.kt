package pl.detailing.crm.communication.rehearsal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RenderedMessageValidatorTest {

    private val values = mapOf("imie" to "Jan", "data" to "04.09.2026", "godzina" to "10:00")

    private fun errors(channel: RehearsalChannel, subject: String?, body: String, v: Map<String, String> = values) =
        RenderedMessageValidator.validate(channel, subject, body, v).filter { it.severity == Severity.ERROR }.map { it.rule }

    @Test
    fun `a clean sms passes`() {
        assertEquals(emptyList<String>(), errors(RehearsalChannel.SMS, null, "Jan, widzimy się 04.09.2026 o 10:00."))
    }

    @Test
    fun `a placeholder with a polish letter is caught even though the renderer ignores it`() {
        val e = errors(RehearsalChannel.SMS, null, "{{imię}}, widzimy się 04.09.2026 o 10:00. Jan")
        assertTrue("placeholder-with-diacritics" in e, e.toString())
    }

    @Test
    fun `single and unbalanced braces are caught`() {
        assertTrue("orphan-braces" in errors(RehearsalChannel.SMS, null, "{imie}, Jan 04.09.2026 10:00"))
        assertTrue("orphan-braces" in errors(RehearsalChannel.SMS, null, "Jan}} 04.09.2026 10:00"))
        assertTrue("orphan-braces" in errors(RehearsalChannel.SMS, null, "Jan {{ 04.09.2026 10:00"))
    }

    @Test
    fun `a value that should be in the text and is not is an error`() {
        val e = errors(RehearsalChannel.SMS, null, "Jan, widzimy się 04.09.2026.")
        assertTrue("value-missing" in e, e.toString())
    }

    @Test
    fun `html in a plain text email is an error`() {
        val e = errors(RehearsalChannel.EMAIL, "Twoja wizyta 04.09.2026", "Dzień dobry <b>Jan</b>, 10:00 zapraszamy na wizytę do studia.")
        assertTrue("html-in-plaintext-email" in e, e.toString())
    }

    @Test
    fun `a multiline subject is an error`() {
        val e = errors(RehearsalChannel.EMAIL, "Twoja\nwizyta", "Jan 04.09.2026 10:00 zapraszamy na wizytę do studia detailingu.")
        assertTrue("subject-multiline" in e)
    }

    @Test
    fun `a leftover null is an error`() {
        assertTrue("template-leftover" in errors(RehearsalChannel.SMS, null, "Jan null 04.09.2026 10:00"))
    }
}
