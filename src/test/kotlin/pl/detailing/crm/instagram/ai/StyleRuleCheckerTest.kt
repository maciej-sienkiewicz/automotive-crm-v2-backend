package pl.detailing.crm.instagram.ai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.instagram.ai.verification.StyleRuleChecker

/**
 * Reguły policzalne liczy kod, nie model.
 *
 * Regresja z produkcji: przy tekście z DOKŁADNIE trzema myślnikami model zwracał
 * „«3 bullet pointy» → 4 bullet pointy", a przy poście bez ani jednego emoji —
 * naruszenie reguły „bez emoji" z uzasadnieniem „brak emoji w poście". Fałszywe
 * naruszenie uruchamiało korektę poprawnego tekstu; w kolejnej rundzie korektor
 * potraktował opis naruszenia jak polecenie i DODAŁ emoji.
 */
class StyleRuleCheckerTest {

    private val checker = StyleRuleChecker()

    private val post = """
        Czy marzysz o perfekcyjnym Porsche Panamera?

        Zalety naszej usługi:
        - Niewidoczna ochrona przed rysami i odpryskami
        - Głębia koloru i maksymalny połysk
        - Dożywotnia gwarancja satysfakcji

        Zarezerwuj termin już dziś.

        #porsche #panamera #detailing
    """.trimIndent()

    @Test
    fun `trzy myslniki to trzy punkty listy, nie cztery`() {
        val verdict = checker.check("3 bullet pointy", post, 1)!!
        assertTrue(verdict.passed, "W tekście są dokładnie trzy punkty: ${verdict.violation}")
    }

    @Test
    fun `czwarty punkt jest wykrywany razem z liczba`() {
        val fourBullets = post.replace(
            "- Dożywotnia gwarancja satysfakcji",
            "- Dożywotnia gwarancja satysfakcji\n- Dojazd do klienta"
        )
        val verdict = checker.check("3 bullet pointy", fourBullets, 1)!!
        assertFalse(verdict.passed)
        assertTrue(verdict.violation!!.contains("4"), "Uzasadnienie podaje policzoną wartość: ${verdict.violation}")
    }

    @Test
    fun `post bez emoji spelnia regule bez emoji`() {
        val verdict = checker.check("bez emoji", post, 1)!!
        assertTrue(verdict.passed)
        assertNull(verdict.violation)
    }

    @Test
    fun `emoji w tekscie lamie regule i trafia do uzasadnienia`() {
        val verdict = checker.check("bez emoji", "$post 🚗", 1)!!
        assertFalse(verdict.passed)
        assertTrue(verdict.violation!!.contains("🚗"), "Uzasadnienie cytuje znalezione emoji: ${verdict.violation}")
    }

    @Test
    fun `limit hashtagow czyta sie jako gorna granice`() {
        assertTrue(checker.check("maksymalnie 5 hashtagów", post, 1)!!.passed)
        assertFalse(checker.check("maksymalnie 2 hashtagi", post, 1)!!.passed)
    }

    @Test
    fun `co najmniej dziala w druga strone`() {
        assertTrue(checker.check("co najmniej 2 hashtagi", post, 1)!!.passed)
        assertFalse(checker.check("co najmniej 8 hashtagów", post, 1)!!.passed)
    }

    @Test
    fun `zakaz bez liczby to zero wystapien`() {
        assertTrue(checker.check("bez wykrzykników", post, 1)!!.passed)
        assertFalse(checker.check("bez wykrzykników", "$post!", 1)!!.passed)
    }

    @Test
    fun `limit znakow liczy dlugosc tekstu`() {
        assertFalse(checker.check("maksymalnie 100 znaków", post, 1)!!.passed)
        assertTrue(checker.check("maksymalnie 5000 znaków", post, 1)!!.passed)
    }

    @Test
    fun `reguly jakosciowe zostawiamy modelowi`() {
        assertNull(checker.check("Pisz ciepłym, bezpośrednim tonem", post, 1))
        assertNull(checker.check("Nie obiecuj efektów, których nie da się zagwarantować", post, 1))
        assertNull(checker.check("Zawsze kończ wezwaniem do działania", post, 1))
    }

    @Test
    fun `numeracja reguly wraca w werdykcie`() {
        assertEquals(4, checker.check("bez emoji", post, 4)!!.ruleIndex)
    }
}
