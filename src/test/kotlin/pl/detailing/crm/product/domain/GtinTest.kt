package pl.detailing.crm.product.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * GTIN jest KLUCZEM TOŻSAMOŚCI produktu i BRAMĄ przed płatnym zapytaniem — literówka
 * kodu ma polec tu, zanim cokolwiek kosztuje.
 */
class GtinTest {

    @Test
    fun `valid EAN-13 normalises to GTIN-14 with leading zero`() {
        // 5901234123457 — poprawna suma kontrolna EAN-13
        val gtin = Gtin.parseOrNull("5901234123457")
        assertNotNull(gtin)
        assertEquals("05901234123457", gtin!!.value)
    }

    @Test
    fun `valid UPC-A normalises to GTIN-14`() {
        // 036000291452 — klasyczny poprawny UPC-A
        val gtin = Gtin.parseOrNull("036000291452")
        assertNotNull(gtin)
        assertEquals("00036000291452", gtin!!.value)
    }

    @Test
    fun `transposed digit fails the checksum`() {
        // 5901234123457 z przestawionymi dwiema cyframi -> zła suma kontrolna
        assertNull(Gtin.parseOrNull("5901234132457"))
    }

    @Test
    fun `spaces and dashes are tolerated`() {
        assertEquals("05901234123457", Gtin.parseOrNull("590-1234 123457")?.value)
    }

    @Test
    fun `non-digits and wrong lengths are rejected`() {
        assertNull(Gtin.parseOrNull("abc"))
        assertNull(Gtin.parseOrNull("12345"))
        assertNull(Gtin.parseOrNull(""))
        assertNull(Gtin.parseOrNull(null))
    }

    @Test
    fun `already-14 GTIN round-trips when checksum holds`() {
        // 00012345678905 — GTIN-14 z poprawną sumą kontrolną
        assertEquals("00012345678905", Gtin.parseOrNull("00012345678905")?.value)
    }
}
