package pl.detailing.crm.instagram.analytics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Werdykt tygodnia to cała logika produktowa zakładki „Tydzień" — jeden wiersz na
 * profil, jedno zdanie. Te testy pilnują, żeby zdanie odpowiadało faktom.
 */
class DigestRulesTest {

    /** Profil obserwowany dostatecznie długo, publikujący zwykle 2 posty tygodniowo. */
    private val typicalBaseline = 2.0

    @Test
    fun `profil ktory zwykle publikuje, a w tym tygodniu nic - CISZA`() {
        val verdict = DigestRules.decide(
            weekPostCount = 0,
            hasBaseline = true,
            medianWeeklyPosts = typicalBaseline,
            hasStandoutPost = false
        )

        assertEquals(DigestVerdict.SILENT, verdict)
    }

    @Test
    fun `brak postow u profilu bez normy to NOWY, nie CISZA`() {
        // „Nie dodał żadnego posta" ma znaczyć, że to u niego nietypowe. Profil
        // obserwowany od tygodnia nie daje podstaw do takiego zdania.
        val verdict = DigestRules.decide(
            weekPostCount = 0,
            hasBaseline = false,
            medianWeeklyPosts = 0.0,
            hasStandoutPost = false
        )

        assertEquals(DigestVerdict.NEW, verdict)
    }

    @Test
    fun `profil ktory normalnie nie publikuje wcale nie jest cichy`() {
        // Mediana ponizej jednego posta tygodniowo: brak publikacji to jego norma.
        val verdict = DigestRules.decide(
            weekPostCount = 0,
            hasBaseline = true,
            medianWeeklyPosts = 0.0,
            hasStandoutPost = false
        )

        assertEquals(DigestVerdict.NEW, verdict)
    }

    @Test
    fun `wyraznie wiecej postow niz zwykle - PRZYSPIESZENIE`() {
        val verdict = DigestRules.decide(
            weekPostCount = 4,
            hasBaseline = true,
            medianWeeklyPosts = typicalBaseline,
            hasStandoutPost = false
        )

        assertEquals(DigestVerdict.ACCELERATED, verdict)
    }

    @Test
    fun `jeden post z ogromnym zaangazowaniem wygrywa z przyspieszeniem`() {
        // Przypadek z brzegu: profil i przyspieszył, i ma hit. Ciekawszy jest hit —
        // to on jest gotową inspiracją, a nie sam fakt większej liczby postów.
        val verdict = DigestRules.decide(
            weekPostCount = 4,
            hasBaseline = true,
            medianWeeklyPosts = typicalBaseline,
            hasStandoutPost = true
        )

        assertEquals(DigestVerdict.STANDOUT, verdict)
    }

    @Test
    fun `pojedynczy post powyzej normy tez jest HITEM`() {
        val verdict = DigestRules.decide(
            weekPostCount = 1,
            hasBaseline = true,
            medianWeeklyPosts = typicalBaseline,
            hasStandoutPost = true
        )

        assertEquals(DigestVerdict.STANDOUT, verdict)
    }

    @Test
    fun `zwykle tempo bez hitu - STABILNIE`() {
        val verdict = DigestRules.decide(
            weekPostCount = 2,
            hasBaseline = true,
            medianWeeklyPosts = typicalBaseline,
            hasStandoutPost = false
        )

        assertEquals(DigestVerdict.STEADY, verdict)
    }

    @Test
    fun `bez normy nie orzekamy o rytmie`() {
        val verdict = DigestRules.decide(
            weekPostCount = 3,
            hasBaseline = false,
            medianWeeklyPosts = 0.0,
            hasStandoutPost = false
        )

        assertEquals(DigestVerdict.NEW, verdict)
    }

    @Test
    fun `jeden post nie jest przyspieszeniem, nawet u milczka`() {
        // Bez progu na liczbę postów profil publikujący raz na kwartał wyskakiwałby
        // jako „zwiększył aktywność" po każdej pojedynczej publikacji.
        assertFalse(
            DigestRules.isAccelerated(weekPostCount = 1, hasBaseline = true, medianWeeklyPosts = 0.2)
        )
        assertEquals(
            DigestVerdict.STEADY,
            DigestRules.decide(1, hasBaseline = true, medianWeeklyPosts = 0.2, hasStandoutPost = false)
        )
    }

    @Test
    fun `norma wymaga i tygodni obserwacji, i postow w historii`() {
        assertTrue(DigestRules.hasBaseline(weeksObserved = 4, baselinePostCount = 6))
        assertFalse(DigestRules.hasBaseline(weeksObserved = 3, baselinePostCount = 40))
        assertFalse(DigestRules.hasBaseline(weeksObserved = 26, baselinePostCount = 5))
    }

    @Test
    fun `progi sa te same, co w pulsie konkurencji`() {
        // Dwa silniki liczyły kiedyś to samo zdarzenie różnymi progami (średnia z 4
        // tygodni vs mediana z 26), więc profil bywał „przyspieszający" na jednym
        // ekranie i zwyczajny na drugim. Jedna definicja na moduł.
        assertEquals(2.5, DigestRules.STANDOUT_FACTOR)
        assertEquals(2.0, DigestRules.ACCELERATION_FACTOR)
        assertEquals(2, DigestRules.ACCELERATION_MIN_POSTS)
    }
}
