package pl.detailing.crm.communication.rehearsal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    private fun warnings(channel: RehearsalChannel, subject: String?, body: String, v: Map<String, String> = values) =
        RenderedMessageValidator.validate(channel, subject, body, v).filter { it.severity == Severity.WARNING }.map { it.rule }

    @Test
    fun `a clean plain text email passes`() {
        assertEquals(emptyList<String>(), errors(RehearsalChannel.EMAIL, "Twoja wizyta 04.09.2026", "Dzień dobry Jan, widzimy się 04.09.2026 o 10:00 w studiu."))
    }

    @Test
    fun `braces in the subject are caught too`() {
        assertTrue("orphan-braces" in errors(RehearsalChannel.EMAIL, "Wizyta {{imie}", "Jan 04.09.2026 10:00 zapraszamy na wizytę do studia detailingu."))
    }

    @Test
    fun `a required value that is blank is an error and an absent optional value is not`() {
        val v = mapOf("imie" to "Jan", "data" to "04.09.2026", "godzina" to "10:00", "link" to "", "rejestracja" to "")
        val e = errors(RehearsalChannel.SMS, null, "Jan 04.09.2026 10:00", v)
        assertTrue("required-empty" in e, e.toString())
        assertEquals(1, e.count { it == "required-empty" })
    }

    @Test
    fun `a link must be absolute https without spaces`() {
        val v = values + ("link" to "http://detailboost.pl/karta/x")
        assertTrue("link-format" in errors(RehearsalChannel.SMS, null, "Jan 04.09.2026 10:00 http://detailboost.pl/karta/x", v))
        val v2 = values + ("link" to "https://detailboost.pl/karta/x y")
        assertTrue("link-format" in errors(RehearsalChannel.SMS, null, "Jan 04.09.2026 10:00 https://detailboost.pl/karta/x y", v2))
        val ok = values + ("link" to "https://detailboost.pl/karta/x")
        assertFalse("link-format" in errors(RehearsalChannel.SMS, null, "Jan 04.09.2026 10:00 https://detailboost.pl/karta/x", ok))
    }

    @Test
    fun `a date without a year is an error when the message carries a date`() {
        val v = mapOf("imie" to "Jan", "data" to "04.09", "godzina" to "10:00")
        assertTrue("date-without-year" in errors(RehearsalChannel.SMS, null, "Jan 04.09 10:00", v))
        val noDate = mapOf("imie" to "Jan")
        assertFalse("date-without-year" in errors(RehearsalChannel.SMS, null, "Jan, dziękujemy", noDate))
    }

    @Test
    fun `sms segment thresholds - two is fine, three warns, four is an error`() {
        val base = "Jan 04.09.2026 10:00 "
        val two = base + "a".repeat(300 - base.length)
        val three = base + "a".repeat(400 - base.length)
        val four = base + "a".repeat(480 - base.length)
        assertFalse("sms-long" in warnings(RehearsalChannel.SMS, null, two))
        assertTrue("sms-long" in warnings(RehearsalChannel.SMS, null, three))
        assertTrue("sms-too-long" in errors(RehearsalChannel.SMS, null, four))
    }

    @Test
    fun `polish letters push the sms into ucs2 so the same length costs more segments`() {
        val prefix = "Jan 04.09.2026 10:00 "
        val polish = prefix + "ł".repeat(179 - prefix.length)   // 179 chars UCS-2 = 3 segments
        val ascii = prefix + "l".repeat(179 - prefix.length)    // 179 chars GSM-7 = 2 segments
        assertTrue("sms-long" in warnings(RehearsalChannel.SMS, null, polish))
        assertFalse("sms-long" in warnings(RehearsalChannel.SMS, null, ascii))
    }

    @Test
    fun `a stray closing brace after a placeholder is an error`() {
        assertTrue("orphan-braces" in errors(RehearsalChannel.SMS, null, "Jan 04.09.2026 10:00 Protokół}"))
        assertTrue("orphan-braces" in errors(RehearsalChannel.SMS, null, "Jan { 04.09.2026 10:00"))
    }

    @Test
    fun `double spaces and edge whitespace are warnings on sms`() {
        assertTrue("whitespace" in warnings(RehearsalChannel.SMS, null, "Jan  04.09.2026 10:00"))
        assertTrue("whitespace" in warnings(RehearsalChannel.SMS, null, " Jan 04.09.2026 10:00"))
        assertFalse("whitespace" in warnings(RehearsalChannel.SMS, null, "Jan 04.09.2026 10:00"))
    }

    @Test
    fun `html in an sms is an error`() {
        assertTrue("html-in-sms" in errors(RehearsalChannel.SMS, null, "Jan <br> 04.09.2026 10:00"))
    }

    @Test
    fun `email subject longer than 78 characters warns and an empty subject is an error`() {
        val body = "Jan 04.09.2026 10:00 zapraszamy na wizytę do studia detailingu w centrum miasta."
        assertTrue("subject-long" in warnings(RehearsalChannel.EMAIL, "T".repeat(79), body))
        assertFalse("subject-long" in warnings(RehearsalChannel.EMAIL, "T".repeat(78), body))
        assertTrue("subject-empty" in errors(RehearsalChannel.EMAIL, "  ", body))
    }

    @Test
    fun `a very short email body warns and an empty one is an error`() {
        assertTrue("body-suspiciously-short" in warnings(RehearsalChannel.EMAIL, "Temat", "Jan 04.09.2026 10:00"))
        assertTrue("body-empty" in errors(RehearsalChannel.EMAIL, "Temat", "", emptyMap()))
    }

    @Test
    fun `a customer value containing braces is data, not a placeholder`() {
        val v = mapOf("imie" to "Jan", "uslugi" to "Pakiet {Premium}")
        // the brace comes from data; the validator still flags it because the customer would see it
        assertTrue("orphan-braces" in errors(RehearsalChannel.SMS, null, "Jan Pakiet {Premium}", v))
    }

    @Test
    fun `leftovers are matched case insensitively`() {
        assertTrue("template-leftover" in errors(RehearsalChannel.SMS, null, "Jan NULL 04.09.2026 10:00"))
        assertTrue("template-leftover" in errors(RehearsalChannel.SMS, null, "Jan undefined 04.09.2026 10:00"))
        assertTrue("template-leftover" in errors(RehearsalChannel.SMS, null, "Jan ${'$'}{imie} 04.09.2026 10:00"))
    }
}
