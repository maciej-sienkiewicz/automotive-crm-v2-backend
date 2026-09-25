package pl.detailing.crm.comms.draft

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Kwota w szkicu to cena podana klientowi. Kod sprawdza, że każda kwota ze szkicu
 * stoi w wycenie leada — model poproszony o przepisanie ceny zwykle to robi, a „zwykle"
 * przy pieniądzach nie wystarcza.
 */
class DraftAmountCheckerTest {

    // 1900,00 zł wpisane jako brutto — kwota nieosiągalna z żadnego netto przy 23% VAT
    // (CLAUDE.md §1). Tu nie wolno jej „poprawić" o grosz w żadną stronę.
    private val allowed = setOf(190_000L, 45_000L, 90_000L)

    @Test
    fun `kwota z wyceny zapisana na rozne sposoby przechodzi`() {
        val text = "Korekta lakieru to 1 900,00 zł, czyli 1900 zł; również 1.900 zł albo 1 900 PLN."
        assertEquals(emptyList<String>(), DraftAmountChecker.unverifiedAmounts(text, allowed))
    }

    @Test
    fun `kwota o grosz inna niz w wycenie jest podejrzana`() {
        val text = "Całość wyniesie 1 900,01 zł."
        assertEquals(listOf("1 900,01 zł"), DraftAmountChecker.unverifiedAmounts(text, allowed))
    }

    @Test
    fun `kwota spoza wyceny i suma policzona przez model sa podejrzane`() {
        val text = "Pranie tapicerki 350 zł. Razem z korektą 2 250,00 zł."
        assertEquals(listOf("350 zł", "2 250,00 zł"), DraftAmountChecker.unverifiedAmounts(text, allowed))
    }

    @Test
    fun `cena za sztuke i razem przy ilosci wiekszej niz jeden przechodza`() {
        val text = "Dwie felgi po 450,00 zł, razem 900,00 zł."
        assertEquals(emptyList<String>(), DraftAmountChecker.unverifiedAmounts(text, allowed))
    }

    @Test
    fun `bez wyceny kazda kwota jest podejrzana`() {
        assertEquals(listOf("500 zł"), DraftAmountChecker.unverifiedAmounts("Około 500 zł.", emptySet()))
    }

    @Test
    fun `liczby bez waluty nie sa kwotami`() {
        val text = "Auto z 2019 roku, 150 000 km, termin za 3 dni, tel. 600 100 200."
        assertTrue(DraftAmountChecker.unverifiedAmounts(text, emptySet()).isEmpty())
    }

    @Test
    fun `znaczniki do uzupelnienia sa wylapywane bez powtorzen`() {
        val text = "Zapraszam [proponowany termin] na oględziny. Adres: [adres studia]. Potwierdzę [proponowany termin]."
        assertEquals(
            listOf("[proponowany termin]", "[adres studia]"),
            DraftAmountChecker.placeholders(text)
        )
    }
}
