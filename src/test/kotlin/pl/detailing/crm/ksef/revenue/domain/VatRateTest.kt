package pl.detailing.crm.ksef.revenue.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class VatRateTest {

    @Test
    fun `liczy VAT od groszy z zaokragleniem HALF_UP`() {
        assertEquals(23_000, VatRate.RATE_23.vatFromNet(100_000))
        assertEquals(23, VatRate.RATE_23.vatFromNet(100))       // 23.00 gr
        assertEquals(2, VatRate.RATE_23.vatFromNet(7))          // 1.61 gr → 2
        assertEquals(800, VatRate.RATE_8.vatFromNet(10_000))
        assertEquals(0, VatRate.RATE_0.vatFromNet(100_000))
        assertEquals(0, VatRate.ZW.vatFromNet(100_000))
    }

    @Test
    fun `parsuje kody FA3 niezaleznie od wielkosci liter`() {
        assertEquals(VatRate.RATE_23, VatRate.fromCode("23"))
        assertEquals(VatRate.ZW, VatRate.fromCode("ZW"))
        assertEquals(VatRate.ZW, VatRate.fromCode(" zw "))
        assertThrows<IllegalArgumentException> { VatRate.fromCode("19") }
    }
}
