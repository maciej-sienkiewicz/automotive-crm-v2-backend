package pl.detailing.crm.communication.whitelist

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource

/**
 * The whitelist is fed from environment variables through `application.properties`; these
 * tests pin down how the comma-separated strings an operator types become lists.
 */
class RecipientWhitelistPropertiesBindingTest {

    private fun bind(vararg pairs: Pair<String, String>): RecipientWhitelistProperties =
        Binder(MapConfigurationPropertySource(pairs.toMap()))
            .bindOrCreate("communication.whitelist", RecipientWhitelistProperties::class.java)

    @Test
    fun `nothing configured means enabled with empty lists`() {
        val p = bind()
        assertTrue(p.enabled)
        assertEquals(emptyList<String>(), p.phones)
        assertEquals(emptyList<String>(), p.emails)
    }

    @Test
    fun `empty env placeholders bind to empty lists, not to a list with one blank entry`() {
        val p = bind("communication.whitelist.phones" to "", "communication.whitelist.emails" to "")
        assertEquals(emptyList<String>(), p.phones)
        assertEquals(emptyList<String>(), p.emails)
    }

    @Test
    fun `comma separated values become one entry each, spaces inside a number preserved`() {
        val p = bind(
            "communication.whitelist.phones" to "+48 500 100 200,601700800",
            "communication.whitelist.emails" to "owner@studio.pl, wspolnik@example.com"
        )
        assertEquals(listOf("+48 500 100 200", "601700800"), p.phones)
        assertEquals(listOf("owner@studio.pl", "wspolnik@example.com"), p.emails.map { it.trim() })
    }

    @Test
    fun `enabled false is honoured`() {
        assertFalse(bind("communication.whitelist.enabled" to "false").enabled)
    }

    @Test
    fun `bound properties feed the whitelist end to end`() {
        val w = RecipientWhitelist(bind("communication.whitelist.phones" to "+48 500 100 200, 601-700-800"))
        assertTrue(w.allowsPhone("500100200"))
        assertTrue(w.allowsPhone("+48601700800"))
        assertFalse(w.allowsPhone("+48999888777"))
    }
}
