package pl.detailing.crm.protocol.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Kwoty w polach protokołu. Wcześniej każde pole — także netto i VAT — dostawało etykietę
 * „(brutto)", a kwota szła przez double.
 */
class CrmDataResolverMoneyFormatTest {

    @Test
    fun `kwota z groszy bez bledow zaokraglen double`() {
        assertEquals("1900.00 PLN (brutto)", CrmDataResolver.formatMoney(190_000, "brutto"))
        assertEquals("1544.72 PLN (netto)", CrmDataResolver.formatMoney(154_472, "netto"))
        assertEquals("355.28 PLN (VAT)", CrmDataResolver.formatMoney(35_528, "VAT"))
    }

    @Test
    fun `male i zerowe kwoty`() {
        assertEquals("0.05 PLN (VAT)", CrmDataResolver.formatMoney(5, "VAT"))
        assertEquals("0.00 PLN (brutto)", CrmDataResolver.formatMoney(0, "brutto"))
    }

    @Test
    fun `duze kwoty bez notacji wykladniczej`() {
        assertEquals("10000000.00 PLN (netto)", CrmDataResolver.formatMoney(1_000_000_000, "netto"))
    }
}
