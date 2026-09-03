package pl.detailing.crm.communication.whitelist

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RecipientWhitelistTest {

    private fun whitelist(
        enabled: Boolean = true,
        phones: List<String> = emptyList(),
        emails: List<String> = emptyList()
    ) = RecipientWhitelist(RecipientWhitelistProperties(enabled, phones, emails))

    @Nested
    inner class Phones {

        private val list = whitelist(phones = listOf("+48 500-100-200", "601 700 800"))

        @Test
        fun `exact E164 entry is allowed`() = assertTrue(list.allowsPhone("+48500100200"))

        @Test
        fun `the same number in every Polish spelling is allowed`() {
            listOf("+48 500 100 200", "500100200", "500-100-200", "0048500100200", "48500100200", "+48500100200")
                .forEach { assertTrue(list.allowsPhone(it), it) }
        }

        @Test
        fun `a nine digit entry matches the customer stored with a plus prefix`() {
            assertTrue(list.allowsPhone("+48601700800"))
            assertTrue(list.allowsPhone("601700800"))
        }

        @Test
        fun `a number that is not on the list is blocked`() {
            assertFalse(list.allowsPhone("+48999888777"))
            assertFalse(list.allowsPhone("999888777"))
        }

        @Test
        fun `a number that differs by one digit is blocked`() = assertFalse(list.allowsPhone("+48500100201"))

        @Test
        fun `a foreign number is blocked unless listed`() {
            assertFalse(list.allowsPhone("+49170123456"))
            assertTrue(whitelist(phones = listOf("+49 170 123456")).allowsPhone("+49170123456"))
        }

        @Test
        fun `garbage that cannot be normalized is always blocked`() {
            listOf("", "   ", "brak", "12345", "telefon do żony").forEach { assertFalse(list.allowsPhone(it), "'$it'") }
        }

        @Test
        fun `a quoted entry (the old properties mistake) still matches, a short one never does`() {
            val sloppy = whitelist(phones = listOf("\"+48606885693\"", "12345"))
            assertTrue(sloppy.allowsPhone("+48606885693"))
            assertFalse(sloppy.allowsPhone("12345"))
        }
    }

    @Nested
    inner class Emails {

        private val list = whitelist(emails = listOf(" Owner@Studio.PL ", "wspolnik@example.com"))

        @Test
        fun `comparison ignores case and surrounding whitespace`() {
            assertTrue(list.allowsEmail("owner@studio.pl"))
            assertTrue(list.allowsEmail("OWNER@STUDIO.PL"))
            assertTrue(list.allowsEmail("  owner@studio.pl "))
        }

        @Test
        fun `an unlisted address is blocked`() {
            assertFalse(list.allowsEmail("klient@gmail.com"))
            assertFalse(list.allowsEmail("owner@studio.com"))
        }

        @Test
        fun `a subaddress or a typo is not the same address`() {
            assertFalse(list.allowsEmail("owner+test@studio.pl"))
            assertFalse(list.allowsEmail("owner@studio.pl.")) 
        }

        @Test
        fun `blank entries in the configuration are ignored`() {
            val w = whitelist(emails = listOf("", " ", "a@b.pl"))
            assertTrue(w.allowsEmail("a@b.pl"))
            assertFalse(w.allowsEmail(""))
        }
    }

    @Nested
    inner class Switch {

        @Test
        fun `enabled with no entries blocks every recipient`() {
            val w = whitelist()
            assertTrue(w.enabled)
            assertFalse(w.allowsPhone("+48500100200"))
            assertFalse(w.allowsEmail("anyone@example.com"))
        }

        @Test
        fun `enabled with phones only still blocks every email`() {
            val w = whitelist(phones = listOf("+48500100200"))
            assertTrue(w.allowsPhone("+48500100200"))
            assertFalse(w.allowsEmail("owner@studio.pl"))
        }

        @Test
        fun `disabled allows everyone including garbage the provider will reject itself`() {
            val w = whitelist(enabled = false)
            assertFalse(w.enabled)
            assertTrue(w.allowsPhone("+48999888777"))
            assertTrue(w.allowsPhone("brak"))
            assertTrue(w.allowsEmail("anyone@example.com"))
        }

        @Test
        fun `the default configuration is enabled and empty, that is fail closed`() {
            val w = RecipientWhitelist(RecipientWhitelistProperties())
            assertTrue(w.enabled)
            assertFalse(w.allowsPhone("+48500100200"))
            assertFalse(w.allowsEmail("owner@studio.pl"))
        }
    }
}
