package pl.detailing.crm.ksef.transfer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Format kodu ZBP 2D co do znaku - aplikacja banku nie wybacza przesuniętego pola.
 * Rachunek PL61 1090 1014 0000 0712 1981 2874 i NIP 526-025-02-74 mają poprawne
 * sumy kontrolne.
 */
class ZbpTransferCodeTest {

    private val nrb = "61109010140000071219812874"

    @Test
    fun `dziewiec pol w kolejnosci ZBP, kwota w groszach, rezerwy puste`() {
        val payload = ZbpTransferCode.payload(
            nip = "5260250274",
            nrb = nrb,
            amountGrosze = 123_000,
            recipientName = "Auto Detailing Pro",
            title = "Faktura FV/12/2026",
        )
        assertEquals("5260250274|PL|61109010140000071219812874|123000|Auto Detailing Pro|Faktura FV/12/2026|||", payload)
    }

    @Test
    fun `kwota uzupelniona zerami do 6 cyfr, a brutto 1900,00 zl to 190000 - nie 190001`() {
        val small = ZbpTransferCode.payload(null, nrb, 1_230, "Odbiorca", "Tytuł")
        assertEquals("|PL|$nrb|001230|Odbiorca|Tytuł|||", small)

        // CLAUDE.md §1: brutto z faktury przechodzi bez przeliczenia z netto 154472 gr.
        val exact = ZbpTransferCode.payload(null, nrb, 190_000, "Odbiorca", "Tytuł")
        assertEquals("190000", exact.split("|")[3])
    }

    @Test
    fun `kwota poza polem ZBP jest odrzucana, a nie zapisywana dluzsza albo ucinana`() {
        assertThrows<IllegalArgumentException> { ZbpTransferCode.payload(null, nrb, 1_000_000, "O", "T") }
        assertThrows<IllegalArgumentException> { ZbpTransferCode.payload(null, nrb, 0, "O", "T") }
        assertEquals("999999", ZbpTransferCode.payload(null, nrb, 999_999, "O", "T").split("|")[3])
    }

    @Test
    fun `numer rachunku ze spacjami, myslnikami, twarda spacja i prefiksem PL daje 26 cyfr`() {
        assertEquals(nrb, ZbpTransferCode.normalizeNrb("PL61 1090 1014 0000 0712 1981 2874"))
        assertEquals(nrb, ZbpTransferCode.normalizeNrb("61-1090-1014-0000-0712-1981-2874"))
        assertEquals(nrb, ZbpTransferCode.normalizeNrb("61 1090 1014 0000 0712 1981 2874"))
        assertEquals(nrb, ZbpTransferCode.normalizeNrb("pl$nrb"))
    }

    @Test
    fun `literowka w rachunku, obcy IBAN i zla dlugosc nie przechodza`() {
        assertNull(ZbpTransferCode.normalizeNrb("61109010140000071219812875"), "zła suma kontrolna")
        assertNull(ZbpTransferCode.normalizeNrb("DE89370400440532013000"), "rachunek zagraniczny")
        assertNull(ZbpTransferCode.normalizeNrb("6110901014000007121981287"), "25 cyfr")
        assertNull(ZbpTransferCode.normalizeNrb(""))
        assertNull(ZbpTransferCode.normalizeNrb(null))
    }

    @Test
    fun `NIP z prefiksem i myslnikami przechodzi, z bledna suma kontrolna wypada`() {
        assertEquals("5260250274", ZbpTransferCode.normalizeNip("PL 526-025-02-74"))
        assertNull(ZbpTransferCode.normalizeNip("5260250275"))
        assertNull(ZbpTransferCode.normalizeNip("123"))
        assertNull(ZbpTransferCode.normalizeNip(null))
    }

    @Test
    fun `tekst bez separatora, cudzyslowow i nowych linii, polskie litery zostaja`() {
        assertEquals(
            "Firma ABC Sp. z o.o.",
            ZbpTransferCode.sanitizeText("  Firma \"ABC\" |\nSp.\tz o.o. ", 32),
        )
        assertEquals("Myjnia Łódź Żółć", ZbpTransferCode.sanitizeText("Myjnia Łódź Żółć", 20))
        assertEquals("Przedsiębiorstwo Han", ZbpTransferCode.sanitizeText("Przedsiębiorstwo Handlowe Kowalski", 20))
    }

    @Test
    fun `tytul niesie numer faktury i nigdy nie ucina go dla ozdobnika`() {
        assertEquals("Faktura FV/12/2026", ZbpTransferCode.title("FV/12/2026"))
        // "Faktura " + 26 znaków = 34 > 32 - zostaje sam numer, cały.
        assertEquals("FV/2026/09/000123/ODD/WAWA", ZbpTransferCode.title("FV/2026/09/000123/ODD/WAWA"))
        assertEquals("Zapłata za fakturę", ZbpTransferCode.title(null))
        assertEquals("Faktura FV 1 2", ZbpTransferCode.title("FV|1|2"))
    }
}
