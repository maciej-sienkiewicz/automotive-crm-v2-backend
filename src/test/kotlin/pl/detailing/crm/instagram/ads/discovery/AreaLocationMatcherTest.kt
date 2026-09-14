package pl.detailing.crm.instagram.ads.discovery

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.instagram.ads.RawAdLocation

/**
 * Dopasowanie reklamy do rejonu. To jest sedno odkrywania: reklamy z całej Polski
 * przychodzą po treści, a decyzję „reklamuje się TU" podejmujemy po targetowaniu.
 *
 * Dwa tryby: albo dokładnie wskazane miasta, albo dodatkowo obszary szersze
 * (województwo, kraj), które i tak docierają na ten teren.
 */
class AreaLocationMatcherTest {

    private fun loc(name: String, type: String = "city", excluded: Boolean = false) =
        RawAdLocation(name = name, type = type, excluded = excluded)

    // ── Tryb: tylko wskazane miasta ──────────────────────────────────────────

    @Test
    fun `tylko miasta - reklama celujaca w to miasto pasuje`() {
        assertTrue(
            AreaLocationMatcher.matches(listOf(loc("Poznań")), listOf("Poznań"), AreaMatchMode.CITIES_ONLY)
        )
    }

    @Test
    fun `tylko miasta - targetowanie na wojewodztwo NIE lapie punktu`() {
        assertFalse(
            AreaLocationMatcher.matches(
                listOf(loc("Wielkopolskie", type = "region")),
                listOf("Poznań"),
                AreaMatchMode.CITIES_ONLY
            )
        )
    }

    @Test
    fun `tylko miasta - inne miasto nie pasuje`() {
        assertFalse(
            AreaLocationMatcher.matches(listOf(loc("Warszawa")), listOf("Poznań"), AreaMatchMode.CITIES_ONLY)
        )
    }

    @Test
    fun `ogonki nie psuja dopasowania - Poznan trafia w Poznań`() {
        assertTrue(
            AreaLocationMatcher.matches(listOf(loc("Poznań")), listOf("Poznan"), AreaMatchMode.CITIES_ONLY)
        )
    }

    // ── Format nazw z Meta: miasto z doklejonym krajem, czasem kilka miejsc ──────

    @Test
    fun `miasto z doklejonym krajem lapie - Meta zwraca Poznań, Polska`() {
        // Regresja: Meta zwraca nazwę miasta jako „Poznań, Polska", a nie samo „Poznań".
        assertTrue(
            AreaLocationMatcher.matches(listOf(loc("Poznań, Polska")), listOf("Poznań"), AreaMatchMode.CITIES_ONLY)
        )
    }

    @Test
    fun `kilka miejsc w jednej nazwie - miasto jako segment po przecinku`() {
        assertTrue(
            AreaLocationMatcher.matches(
                listOf(loc("Kleszczewo, Poznań, Poland, Polska")),
                listOf("Poznań"),
                AreaMatchMode.CITIES_ONLY
            )
        )
    }

    @Test
    fun `wielowyrazowa miejscowosc z krajem - Suchy Las, Polska`() {
        assertTrue(
            AreaLocationMatcher.matches(listOf(loc("Suchy Las, Polska")), listOf("Suchy Las"), AreaMatchMode.CITIES_ONLY)
        )
    }

    @Test
    fun `wykluczone Poznań, Polska blokuje mimo formatu z krajem`() {
        val ad = listOf(loc("Polska", type = "country"), loc("Poznań, Polska", excluded = true))
        assertFalse(AreaLocationMatcher.matches(ad, listOf("Poznań"), AreaMatchMode.INCLUDE_BROADER))
    }

    @Test
    fun `segment kraju w nazwie nie myli sie z miastem`() {
        // „Poznań, Polska" nie może zaliczyć zapytania o „Polska" jako miasto.
        assertFalse(
            AreaLocationMatcher.matches(listOf(loc("Warszawa, Polska")), listOf("Poznań"), AreaMatchMode.CITIES_ONLY)
        )
    }

    // ── Tryb: szersze obszary ────────────────────────────────────────────────

    @Test
    fun `szersze obszary - wojewodztwo miejscowosci lapie punkt`() {
        assertTrue(
            AreaLocationMatcher.matches(
                listOf(loc("Wielkopolskie", type = "region")),
                listOf("Poznań"),
                AreaMatchMode.INCLUDE_BROADER
            )
        )
    }

    @Test
    fun `szersze obszary - angielska nazwa wojewodztwa z Meta tez lapie`() {
        assertTrue(
            AreaLocationMatcher.matches(
                listOf(loc("Greater Poland Voivodeship", type = "region")),
                listOf("Suchy Las"),
                AreaMatchMode.INCLUDE_BROADER
            )
        )
    }

    @Test
    fun `szersze obszary - cala Polska obejmuje kazde miasto`() {
        assertTrue(
            AreaLocationMatcher.matches(
                listOf(loc("Poland", type = "country")),
                listOf("Skórzewo"),
                AreaMatchMode.INCLUDE_BROADER
            )
        )
    }

    @Test
    fun `szersze obszary - obce wojewodztwo NIE lapie punktu`() {
        assertFalse(
            AreaLocationMatcher.matches(
                listOf(loc("Mazowieckie", type = "region")),
                listOf("Poznań"),
                AreaMatchMode.INCLUDE_BROADER
            )
        )
    }

    // ── Wykluczenia ──────────────────────────────────────────────────────────

    @Test
    fun `wykluczenie miasta ubija je mimo targetu na caly kraj`() {
        val ad = listOf(loc("Poland", type = "country"), loc("Poznań", excluded = true))
        assertFalse(AreaLocationMatcher.matches(ad, listOf("Poznań"), AreaMatchMode.INCLUDE_BROADER))
    }

    @Test
    fun `wykluczenie jednego miasta nie ubija drugiego z tego samego zapytania`() {
        // Kraj z wykluczonym Poznaniem: zapytanie o Poznań i Suchy Las trafia przez Suchy Las.
        val ad = listOf(loc("Poland", type = "country"), loc("Poznań", excluded = true))
        assertTrue(
            AreaLocationMatcher.matches(ad, listOf("Poznań", "Suchy Las"), AreaMatchMode.INCLUDE_BROADER)
        )
    }

    // ── Wejście brzegowe ─────────────────────────────────────────────────────

    @Test
    fun `brak wskazanych miejscowosci to brak dopasowania`() {
        assertFalse(AreaLocationMatcher.matches(listOf(loc("Poznań")), emptyList(), AreaMatchMode.INCLUDE_BROADER))
    }

    @Test
    fun `dowolne trafienie z listy miejscowosci wystarczy`() {
        assertTrue(
            AreaLocationMatcher.matches(
                listOf(loc("Kraków")),
                listOf("Poznań", "Kraków", "Gdańsk"),
                AreaMatchMode.CITIES_ONLY
            )
        )
    }
}
