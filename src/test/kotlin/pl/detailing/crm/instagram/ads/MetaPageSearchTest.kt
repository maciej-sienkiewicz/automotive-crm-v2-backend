package pl.detailing.crm.instagram.ads

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * Podpowiedzi stron przy powiązaniu profilu.
 *
 * `search_terms` przeszukuje TREŚĆ reklam, nie nazwy stron — więc zapytanie
 * „Car Art Detailing" wraca z każdym, kto ma w tekście „car" i „detailing".
 * Pomyłka na tej liście kosztuje podpięcie cudzej firmy pod nazwą konkurenta
 * i wszystkie jej reklamy w kalendarzu, więc odsiew jest tu funkcją, nie kosmetyką.
 */
class MetaPageSearchTest {

    private fun page(name: String, ads: Int = 1, last: LocalDate? = LocalDate.of(2026, 9, 1)) =
        MetaPageCandidate(pageId = name.hashCode().toString(), pageName = name, ads = ads, lastStart = last)

    @Test
    fun `firma bez slowa z zapytania w nazwie nie jest podpowiedzia`() {
        val result = MetaPageSearch.rank(
            "Car Art Detailing",
            listOf(
                page("Car Art Detailing"),
                page("RileyRiver Clothing"),
                page("Theo Sterling Apparel")
            )
        )

        assertEquals(listOf("Car Art Detailing"), result.map { it.pageName })
    }

    @Test
    fun `pelna nazwa bije czesciowe trafienie, nawet gdy tamten reklamuje sie czesciej`() {
        val result = MetaPageSearch.rank(
            "Car Art Detailing",
            listOf(
                page("Detailing Kraków", ads = 40),
                page("Car Art Detailing", ads = 2)
            )
        )

        assertEquals("Car Art Detailing", result.first().pageName)
    }

    @Test
    fun `przy tym samym trafieniu wyzej stoi ten, kto reklamuje sie intensywniej`() {
        val result = MetaPageSearch.rank(
            "detailing",
            listOf(page("ZEN Detailingu", ads = 1), page("Easy PPF & Detailing", ads = 9))
        )

        assertEquals("Easy PPF & Detailing", result.first().pageName)
    }

    @Test
    fun `ogonki i myslniki nie psuja dopasowania`() {
        val result = MetaPageSearch.rank("zolw detailing", listOf(page("ŻÓŁW-Detailing")))

        assertEquals(1, result.size)
    }

    /**
     * Pusta lista jest uczciwsza niż lista przypadkowych firm: człowiek dostaje
     * wtedy komunikat „nie znaleziono" i pole na ręczny numer.
     */
    @Test
    fun `brak trafienia w nazwie daje pustke, a nie liste losowych firm`() {
        val result = MetaPageSearch.rank(
            "Car Art Detailing",
            listOf(page("RileyRiver Clothing"), page("Oceaniaify cool"))
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `zapytanie z samych krotkich slow nic nie podpowiada`() {
        assertTrue(MetaPageSearch.rank("a i w", listOf(page("Auto Spa"))).isEmpty())
    }
}
