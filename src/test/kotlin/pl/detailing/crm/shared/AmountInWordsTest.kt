package pl.detailing.crm.shared

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Kwota słownie jest na fakturze jedynym zabezpieczeniem przed dopisaniem cyfry do
 * kwoty na wydruku, więc błąd w odmianie nie jest kosmetyką: „tysiąc dwieście
 * trzynaście złote" podważa cały dokument.
 */
class AmountInWordsTest {

    @Test
    fun `odmiana złotego po liczebniku`() {
        assertEquals("jeden złoty 00/100", AmountInWords.format(100))
        assertEquals("dwa złote 00/100", AmountInWords.format(200))
        assertEquals("pięć złotych 00/100", AmountInWords.format(500))
        assertEquals("dwadzieścia dwa złote 00/100", AmountInWords.format(2_200))
        // Nastki biorą formę „złotych" mimo końcówki 2-4.
        assertEquals("dwanaście złotych 00/100", AmountInWords.format(1_200))
        assertEquals("trzynaście złotych 00/100", AmountInWords.format(1_300))
        assertEquals("czternaście złotych 00/100", AmountInWords.format(1_400))
    }

    @Test
    fun `grosze zostają cyframi`() {
        assertEquals("dwa tysiące dwieście czternaście złotych 14/100", AmountInWords.format(221_414))
        assertEquals("zero złotych 05/100", AmountInWords.format(5))
    }

    @Test
    fun `tysiące, miliony i setki`() {
        assertEquals("tysiąc złotych 00/100", AmountInWords.format(100_000))
        assertEquals("dwa tysiące złotych 00/100", AmountInWords.format(200_000))
        assertEquals("pięć tysięcy złotych 00/100", AmountInWords.format(500_000))
        assertEquals("milion złotych 00/100", AmountInWords.format(100_000_000))
        assertEquals("dziewięćset dziewięćdziesiąt dziewięć złotych 99/100", AmountInWords.format(99_999))
    }

    @Test
    fun `korekta na minus czyta się jako minus`() {
        assertEquals("minus sto złotych 00/100", AmountInWords.format(-10_000))
    }
}
