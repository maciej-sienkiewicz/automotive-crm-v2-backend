package pl.detailing.crm.product.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.product.PriceInput

/**
 * Reguła brutto z CLAUDE.md §1 na jedynym polu pieniężnym modułu (cena jednostkowa).
 * Kwota wpisana przez człowieka JEST zapisywana bez zmian; druga strona wynika z niej,
 * liczona raz. VAT to RÓŻNICA pokazanych kwot.
 */
class ProductPriceResolverTest {

    private val resolver = ProductPriceResolver()

    @Test
    fun `gross entered as 1900,00 does not float back to 1900,01`() {
        // To jest dokładnie zgłoszenie z produkcji: 190000 gr brutto przy 23% ma zostać
        // 190000, a nie 190001 (netto 154472 gr × 1,23 = 190000,56 → zaokrągliłoby w górę).
        val r = resolver.resolve(PriceInput(unitPriceNet = null, unitPriceGross = 190000, priceEnteredAs = "GROSS", vatRate = 23))!!
        assertEquals(190000, r.grossCents) { "Brutto wpisane przez człowieka nie może się zmienić" }
        assertEquals("GROSS", r.enteredAs)
        // VAT jako różnica pokazanych kwot
        assertEquals(35528, r.grossCents - r.netCents)
    }

    @Test
    fun `net entered derives gross once`() {
        val r = resolver.resolve(PriceInput(unitPriceNet = 154472, unitPriceGross = null, priceEnteredAs = "NET", vatRate = 23))!!
        assertEquals(154472, r.netCents) { "Netto wpisane przez człowieka nie może się zmienić" }
        assertEquals("NET", r.enteredAs)
        // 154472 × 1,23 = 190000,56 → 190001
        assertEquals(190001, r.grossCents)
    }

    @Test
    fun `net with provided exact gross keeps the exact gross`() {
        // Front zna dokładne brutto z formularza — resolveGrossAmount przepuszcza je
        // (tolerancja 1 grosza), zamiast liczyć 190001.
        val r = resolver.resolve(PriceInput(unitPriceNet = 154472, unitPriceGross = 190000, priceEnteredAs = "NET", vatRate = 23))!!
        assertEquals(154472, r.netCents)
        assertEquals(190000, r.grossCents)
    }

    @Test
    fun `vat-exempt keeps net equal to gross`() {
        val r = resolver.resolve(PriceInput(unitPriceNet = null, unitPriceGross = 5000, priceEnteredAs = "GROSS", vatRate = -1))!!
        assertEquals(5000, r.grossCents)
        assertEquals(5000, r.netCents)
    }

    @Test
    fun `null input yields null`() {
        assertEquals(null, resolver.resolve(null))
    }
}
