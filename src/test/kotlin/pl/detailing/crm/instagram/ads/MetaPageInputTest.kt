package pl.detailing.crm.instagram.ads

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Rozpoznanie tego, co ktoś wkleił w pole „strona konkurenta".
 *
 * Facebook pokazuje tę samą stronę pod trzema postaciami naraz — numerem,
 * aliasem i adresem biblioteki reklam — a użytkownik trafia na tę, która akurat
 * mu się pokazała. Kazać mu rozpoznawać, którą ma przed sobą, to przerzucanie
 * na niego naszej roboty; te testy przybijają, że robimy ją za niego.
 */
class MetaPageInputTest {

    private fun id(raw: String) = (MetaPageInput.parse(raw) as PageInput.Id).pageId
    private fun term(raw: String) = (MetaPageInput.parse(raw) as PageInput.Term).term

    @Test
    fun `sam numer jest numerem`() {
        assertEquals("100064043405405", id("100064043405405"))
        assertEquals("176612669486327", id("  176612669486327  "))
    }

    @Test
    fun `numer z adresu profilu`() {
        assertEquals("100064043405405", id("https://www.facebook.com/profile.php?id=100064043405405"))
    }

    @Test
    fun `numer z adresu biblioteki reklam`() {
        assertEquals(
            "176612669486327",
            id("https://www.facebook.com/ads/library/?active_status=all&country=PL&view_all_page_id=176612669486327")
        )
    }

    @Test
    fun `numer ze starego adresu z nazwą w środku`() {
        assertEquals("123456789012", id("https://www.facebook.com/pages/Car-Art-Detailing/123456789012"))
    }

    @Test
    fun `alias z adresu strony`() {
        assertEquals("CarArtDetailing", term("https://www.facebook.com/CarArtDetailing"))
        assertEquals("CarArtDetailing", term("facebook.com/CarArtDetailing/"))
        assertEquals("carslab_pl", term("https://www.instagram.com/carslab_pl/"))
    }

    @Test
    fun `nazwa firmy wpisana z ręki zostaje nazwą`() {
        assertEquals("Car Art Detailing", term("Car Art Detailing"))
        assertEquals("carartdetailing", term("@carartdetailing"))
    }

    /** Krótki ciąg cyfr to nie identyfikator strony, tylko numer z nazwy albo rok. */
    @Test
    fun `krotka liczba nie udaje identyfikatora`() {
        assertEquals("2026", term("2026"))
    }

    @Test
    fun `puste wejscie nie jest niczym`() {
        assertEquals(PageInput.Empty, MetaPageInput.parse("   "))
        assertEquals(PageInput.Empty, MetaPageInput.parse(null))
    }

    /**
     * Biblioteka reklam nie zna aliasów — szuka po treści reklam, a nazwy stron
     * dopasowujemy dopiero u siebie. Alias pisany łącznie nie trafiłby w nic.
     */
    @Test
    fun `alias rozbija sie na slowa do wyszukania`() {
        assertEquals("Car Art Detailing", MetaPageInput.toSearchTerm("CarArtDetailing"))
        assertEquals("carslab pl", MetaPageInput.toSearchTerm("carslab_pl"))
        assertEquals("zen detailingu", MetaPageInput.toSearchTerm("zen-detailingu"))
        assertEquals("Auto Spa Kraków", MetaPageInput.toSearchTerm("Auto Spa Kraków"))
    }

    /** Polskie litery też są granicą słowa — „ŻółwDetailing" ma się rozpaść. */
    @Test
    fun `wielka polska litera zaczyna nowe slowo`() {
        assertEquals("Żółw Detailing", MetaPageInput.toSearchTerm("ŻółwDetailing"))
    }
}
