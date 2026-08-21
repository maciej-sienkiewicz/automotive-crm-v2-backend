package pl.detailing.crm.costs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CostItemAmountsTest {

    @Test
    fun `zwraca kwoty z faktury gdy obie stron sa wypelnione`() {
        assertEquals(347.38, CostItemAmounts.netValueOf(347.38, 427.28, "23"))
        assertEquals(427.28, CostItemAmounts.grossValueOf(347.38, 427.28, "23"))
    }

    @Test
    fun `wylicza netto z brutto metoda w stu`() {
        // AGT AUTO SERWIS FS 54/07/2026 — sprzedawca podał tylko brutto.
        assertEquals(5400.00, CostItemAmounts.netValueOf(null, 6642.00, "23"))
        // Bilet kolejowy, stawka 8%.
        assertEquals(85.19, CostItemAmounts.netValueOf(null, 92.00, "8"))
    }

    @Test
    fun `dla stawek bez VAT netto rowna sie brutto`() {
        // Usługa księgowa "zw", prowizja "np", odwrotne obciążenie "oo", eksport "0".
        assertEquals(1900.00, CostItemAmounts.netValueOf(null, 1900.00, "zw"))
        assertEquals(132.45,  CostItemAmounts.netValueOf(null, 132.45,  "np"))
        assertEquals(500.00,  CostItemAmounts.netValueOf(null, 500.00,  "oo"))
        assertEquals(250.00,  CostItemAmounts.netValueOf(null, 250.00,  "0 KR"))
    }

    @Test
    fun `dolicza brutto do netto`() {
        assertEquals(427.28, CostItemAmounts.grossValueOf(347.38, null, "23"))
        assertEquals(169.99, CostItemAmounts.grossValueOf(157.40, null, "8"))
        assertEquals(132.45, CostItemAmounts.grossValueOf(132.45, null, "zw"))
    }

    @Test
    fun `nierozpoznana stawka nie jest zgadywana`() {
        assertNull(CostItemAmounts.netValueOf(null, 100.00, null))
        assertNull(CostItemAmounts.netValueOf(null, 100.00, ""))
        assertNull(CostItemAmounts.netValueOf(null, 100.00, "b/d"))
        assertNull(CostItemAmounts.grossValueOf(100.00, null, "b/d"))
    }

    @Test
    fun `brak obu kwot zostaje pusty`() {
        assertNull(CostItemAmounts.netValueOf(null, null, "23"))
        assertNull(CostItemAmounts.grossValueOf(null, null, "23"))
    }

    @Test
    fun `czyta stawke zapisana z procentem i spacjami`() {
        assertEquals(0.23, CostItemAmounts.rateOf(" 23 % ")?.toDouble())
        assertEquals(0.0,  CostItemAmounts.rateOf("ZW")?.toDouble())
    }

    @Test
    fun `zaokraglenie do grosza jest polowkowe w gore`() {
        // 10,00 / 1,23 = 8,130081… → 8,13
        assertEquals(8.13, CostItemAmounts.netValueOf(null, 10.00, "23"))
        // 54,10 / 1,23 = 43,983739… → 43,98
        assertEquals(43.98, CostItemAmounts.netValueOf(null, 54.10, "23"))
    }
}
