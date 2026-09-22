package pl.detailing.crm.visit.transitions.complete

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Netto reszty kwoty wizyty poza fakturą (paragon „reszta"). Wcześniej zawsze brutto ÷ 1,23,
 * więc usługa na 8% albo zwolniona z VAT, której faktura nie objęła, trafiała do przychodu
 * ze złym netto i złym VAT-em.
 */
class RemainderNetCentsTest {

    @Test
    fun `reszta z uslugi na 8 procent ma VAT 8 procent, nie 23`() {
        // Wizyta: 1000,00 netto/1230,00 brutto (23%) + 100,00 netto/108,00 brutto (8%).
        // Faktura objęła tylko usługę na 23%, reszta to 108,00 zł.
        val net = remainderNetCents(visitNetCents = 110_000, invoiceNetCents = 100_000, remainderGrossCents = 10_800)

        assertEquals(10_000L, net)
        assertEquals(800L, 10_800 - net) // VAT = brutto − netto
    }

    @Test
    fun `reszta z uslugi zwolnionej z VAT ma netto rowne brutto`() {
        val net = remainderNetCents(visitNetCents = 105_000, invoiceNetCents = 100_000, remainderGrossCents = 5_000)

        assertEquals(5_000L, net)
    }

    @Test
    fun `reszta 23 procent - ta sama kwota co dawniej`() {
        // 1900,00 brutto na fakturze nie było: 154472 netto wizyty, faktura 0.
        val net = remainderNetCents(visitNetCents = 154_472, invoiceNetCents = 0, remainderGrossCents = 190_000)

        assertEquals(154_472L, net)
        assertEquals(35_528L, 190_000 - net)
    }

    @Test
    fun `faktura z netto wiekszym niz wizyta - wraca dawny podzial po 23 procent zamiast ujemnego netta`() {
        val net = remainderNetCents(visitNetCents = 100_000, invoiceNetCents = 100_500, remainderGrossCents = 500)

        assertEquals(407L, net) // 500 · 100 / 123 = 406,5 → 407
    }

    @Test
    fun `netto wieksze niz brutto reszty - tez dawny podzial, bo VAT nie moze byc ujemny`() {
        val net = remainderNetCents(visitNetCents = 200_000, invoiceNetCents = 100_000, remainderGrossCents = 50_000)

        assertEquals(40_650L, net) // 50000 · 100 / 123 = 40650,4 → 40650
    }
}
