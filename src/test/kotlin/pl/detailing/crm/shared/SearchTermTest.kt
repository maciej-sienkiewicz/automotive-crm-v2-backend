package pl.detailing.crm.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Wyszukiwarka faktur ma odpowiadać na to, co człowiek ma przed oczami — NIP z myślnikami,
 * kwotę z przecinkiem — a nie na wewnętrzną reprezentację w bazie. Te przypadki pilnują
 * tłumaczenia jednej frazy na warianty, które zapytanie potrafi porównać.
 */
class SearchTermTest {

    @Test
    fun `pusta fraza nie filtruje niczego`() {
        assertNull(SearchTerm.like(null))
        assertNull(SearchTerm.like("   "))
        assertNull(SearchTerm.digitsLike("  "))
        assertNull(SearchTerm.digitsLike(null))
        assertNull(SearchTerm.amountLike(""))
    }

    @Test
    fun `fraza tekstowa jest wzorcem LIKE malymi literami`() {
        assertEquals("%auto serwis%", SearchTerm.like("  Auto Serwis "))
    }

    @Test
    fun `znaki wieloznaczne wpisane we fraze sa literalami`() {
        assertEquals("""%100\%%""", SearchTerm.like("100%"))
        assertEquals("""%fv\_2026%""", SearchTerm.like("FV_2026"))
    }

    @Test
    fun `NIP wpisany z myslnikami i prefiksem sprowadza sie do cyfr`() {
        assertEquals("%1234563218%", SearchTerm.digitsLike("123-456-32-18"))
        assertEquals("%1234563218%", SearchTerm.digitsLike("PL 1234563218"))
    }

    @Test
    fun `fraza bez cyfr nie szuka po NIP`() {
        assertNull(SearchTerm.digitsLike("Auto Serwis"))
    }

    @Test
    fun `pojedyncza cyfra we frazie nie uruchamia dopasowania po NIP`() {
        // `%5%` pasuje do prawie każdego NIP-u — taki wynik byłby szumem obok trafień po nazwie.
        assertNull(SearchTerm.digitsLike("auto 5"))
        assertEquals("%123%", SearchTerm.digitsLike("123"))
    }

    @Test
    fun `kwota normalizuje przecinek i spacje do formatu zapytania`() {
        assertEquals("%1230.50%", SearchTerm.amountLike("1 230,50"))
        assertEquals("%1230.50%", SearchTerm.amountLike("1230.50"))
        // Sama część całkowita ma trafiać w 1230,50 — dopasowanie jest fragmentem.
        assertEquals("%1230%", SearchTerm.amountLike("1230"))
    }

    @Test
    fun `fraza ktora nie jest kwota nie tyka kolumn kwotowych`() {
        assertNull(SearchTerm.amountLike("FV/2026/01"))
        assertNull(SearchTerm.amountLike("123-456-32-18"))
        assertNull(SearchTerm.amountLike("1230,505"))
    }
}
