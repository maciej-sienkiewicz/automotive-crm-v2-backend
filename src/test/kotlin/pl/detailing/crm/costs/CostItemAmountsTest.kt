package pl.detailing.crm.costs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Kwoty są w GROSZACH (V80) — 347,38 zł zapisujemy jako 34738.
 * Komentarze podają kwoty w złotych, żeby przypadki dało się czytać.
 */
class CostItemAmountsTest {

    @Test
    fun `zwraca kwoty z faktury gdy obie stron sa wypelnione`() {
        assertEquals(34738L, CostItemAmounts.netValueOf(34738L, 42728L, "23"))
        assertEquals(42728L, CostItemAmounts.grossValueOf(34738L, 42728L, "23"))
    }

    @Test
    fun `wylicza netto z brutto metoda w stu`() {
        // AGT AUTO SERWIS FS 54/07/2026 — sprzedawca podał tylko brutto: 6642,00 → 5400,00
        assertEquals(540000L, CostItemAmounts.netValueOf(null, 664200L, "23"))
        // Bilet kolejowy, stawka 8%: 92,00 → 85,19
        assertEquals(8519L, CostItemAmounts.netValueOf(null, 9200L, "8"))
    }

    @Test
    fun `dla stawek bez VAT netto rowna sie brutto`() {
        // Usługa księgowa "zw", prowizja "np", odwrotne obciążenie "oo", eksport "0".
        assertEquals(190000L, CostItemAmounts.netValueOf(null, 190000L, "zw"))
        assertEquals(13245L,  CostItemAmounts.netValueOf(null, 13245L,  "np"))
        assertEquals(50000L,  CostItemAmounts.netValueOf(null, 50000L,  "oo"))
        assertEquals(25000L,  CostItemAmounts.netValueOf(null, 25000L,  "0 KR"))
    }

    @Test
    fun `dolicza brutto do netto`() {
        assertEquals(42728L, CostItemAmounts.grossValueOf(34738L, null, "23"))
        assertEquals(16999L, CostItemAmounts.grossValueOf(15740L, null, "8"))
        assertEquals(13245L, CostItemAmounts.grossValueOf(13245L, null, "zw"))
    }

    @Test
    fun `nierozpoznana stawka nie jest zgadywana`() {
        assertNull(CostItemAmounts.netValueOf(null, 10000L, null))
        assertNull(CostItemAmounts.netValueOf(null, 10000L, ""))
        assertNull(CostItemAmounts.netValueOf(null, 10000L, "b/d"))
        assertNull(CostItemAmounts.grossValueOf(10000L, null, "b/d"))
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
        assertEquals(813L, CostItemAmounts.netValueOf(null, 1000L, "23"))
        // 54,10 / 1,23 = 43,983739… → 43,98
        assertEquals(4398L, CostItemAmounts.netValueOf(null, 5410L, "23"))
    }

    @Test
    fun `grosze nie gubia sie na zaokragleniu jak na double`() {
        // 45,45 zł: 4545 groszy dokładnie. Na double 45.45 * 100 daje 4544.999999999999,
        // przez co porównanie „co do grosza" dwóch faktur o tej samej kwocie zawodziło.
        assertEquals(4545L, CostItemAmounts.grossValueOf(4545L, null, "zw"))
    }
}
