package pl.detailing.crm.product.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Wewnątrz kluczujemy katalog GTIN-em-14 (żeby EAN-13 i UPC-A tego samego produktu
 * trafiły w jeden wiersz), ale NA ZEWNĄTRZ — do wyszukiwarki, do API baz kodów i do
 * promptu modelu — musi iść postać DRUKOWANA.
 *
 * To nie jest kosmetyka: „05902806493015" nie znajduje w sieci niczego, a
 * „5902806493015" zwraca oferty tego produktu. Zgłoszenie z produkcji wskazało dokładnie
 * tę różnicę.
 */
class GtinDisplayValueTest {

    @Test
    fun `EAN-13 traci wiodace zero dopelniajace do 14`() {
        val gtin = Gtin.parse("5902806493015")
        assertEquals("05902806493015", gtin.value, "wewnętrznie: GTIN-14")
        assertEquals("5902806493015", gtin.displayValue, "na zewnątrz: postać drukowana")
    }

    @Test
    fun `kod podany juz jako GTIN-14 tez wraca w postaci drukowanej`() {
        assertEquals("5902806493015", Gtin.parse("05902806493015").displayValue)
    }

    @Test
    fun `UPC-A zostaje 12-cyfrowy, a EAN-8 osmiocyfrowy`() {
        assertEquals(12, Gtin.parse("036000291452").displayValue.length)
        assertEquals(8, Gtin.parse("96385074").displayValue.length)
    }
}
