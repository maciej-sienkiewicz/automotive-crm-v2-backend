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
    fun `liczy VAT od brutto wzorem w stu — brutto pozostaje dokladne`() {
        // 500,00 zł brutto @23%: VAT = 50000×23/123 = 9349.59 → 9350; netto 40650
        assertEquals(9_350, VatRate.RATE_23.vatFromGross(50_000))
        assertEquals(50_000, (50_000 - VatRate.RATE_23.vatFromGross(50_000)) + VatRate.RATE_23.vatFromGross(50_000))

        // 100,00 zł brutto @8%: VAT = 10000×8/108 = 740.74 → 741
        assertEquals(741, VatRate.RATE_8.vatFromGross(10_000))

        // Niezmiennik dla dowolnej kwoty: netto (= brutto − VAT) + VAT == brutto
        for (gross in longArrayOf(1, 99, 4_999, 50_000, 123_456, 999_999)) {
            val vat = VatRate.RATE_23.vatFromGross(gross)
            assertEquals(gross, (gross - vat) + vat)
        }

        assertEquals(0, VatRate.RATE_0.vatFromGross(50_000))
        assertEquals(0, VatRate.ZW.vatFromGross(50_000))
    }

    @Test
    fun `parsuje kody FA3 niezaleznie od wielkosci liter`() {
        assertEquals(VatRate.RATE_23, VatRate.fromCode("23"))
        assertEquals(VatRate.ZW, VatRate.fromCode("ZW"))
        assertEquals(VatRate.ZW, VatRate.fromCode(" zw "))
        assertThrows<IllegalArgumentException> { VatRate.fromCode("19") }
    }

    /**
     * Enumeracja TStawkaPodatku ze schematu FA(3) nie dopuszcza samego „0" —
     * stawka zerowa jest rozbita na krajową, WDT i eksportową. Faktura z P_12=0
     * wracała z KSeF z kodem 450 (Enumeration constraint failed).
     */
    @Test
    fun `stawka zerowa idzie do P_12 jako 0 KR, a nie jako 0`() {
        assertEquals("0", VatRate.RATE_0.code)
        assertEquals("0 KR", VatRate.RATE_0.p12Code)
    }

    @Test
    fun `pozostale stawki maja kod wewnetrzny rowny wartosci P_12`() {
        listOf(VatRate.RATE_23, VatRate.RATE_8, VatRate.RATE_5, VatRate.ZW).forEach {
            assertEquals(it.code, it.p12Code, "Stawka ${it.name} nie powinna mieć osobnego kodu P_12")
        }
    }

    @Test
    fun `stawke zerowa mozna podac zarowno kodem wewnetrznym jak i wartoscia z P_12`() {
        assertEquals(VatRate.RATE_0, VatRate.fromCode("0"))
        assertEquals(VatRate.RATE_0, VatRate.fromCode("0 KR"))
        assertEquals(VatRate.RATE_0, VatRate.fromCode("0 kr"))
        assertEquals(VatRate.RATE_0, VatRate.fromCode(" 0  KR "))
        // Stawki spoza zakresu modułu nie mogą przejść po cichu jako krajowe zero
        assertThrows<IllegalArgumentException> { VatRate.fromCode("0 WDT") }
        assertThrows<IllegalArgumentException> { VatRate.fromCode("0 EX") }
    }
}
