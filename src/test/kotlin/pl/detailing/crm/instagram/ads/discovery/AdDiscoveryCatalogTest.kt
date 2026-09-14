package pl.detailing.crm.instagram.ads.discovery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Katalog fraz odkrywania.
 *
 * Katalog jest kontraktem z bazą: identyfikatory trafiają do wykluczeń studiów, a
 * teksty — do wspólnego cache i do Meta. Dwie pomyłki kosztują tu naprawdę:
 * zduplikowany `id` cicho osieroci czyjeś ustawienia, a zduplikowany tekst to dwa
 * pobrania tej samej frazy z limitu 180 wywołań na godzinę dla całej instalacji.
 */
class AdDiscoveryCatalogTest {

    @Test
    fun `identyfikatory sa unikalne`() {
        val duplicates = AdDiscoveryCatalog.ALL.groupingBy { it.id }.eachCount().filterValues { it > 1 }
        assertTrue(duplicates.isEmpty(), "Powtórzone identyfikatory: ${duplicates.keys}")
    }

    @Test
    fun `teksty fraz sa unikalne po normalizacji`() {
        val duplicates = AdDiscoveryCatalog.ALL
            .groupingBy { AdDiscoveryPhrase.normalize(it.text) }
            .eachCount()
            .filterValues { it > 1 }
        assertTrue(duplicates.isEmpty(), "Powtórzone frazy: ${duplicates.keys}")
    }

    @Test
    fun `kazda fraza przechodzi walidacje dlugosci`() {
        val invalid = AdDiscoveryCatalog.ALL.filter { AdDiscoveryPhrase.normalizeValid(it.text) == null }
        assertTrue(invalid.isEmpty(), "Frazy odrzucone przez walidację: ${invalid.map { it.id }}")
    }

    @Test
    fun `identyfikatory nie zawieraja separatora listy`() {
        // Wykluczenia lądują w jednej kolumnie rozdzielone „|" — pionowa kreska w id
        // rozbiłaby zapis na dwa nieistniejące identyfikatory.
        val bad = AdDiscoveryCatalog.ALL.filter { it.id.contains('|') || it.id.isBlank() }
        assertTrue(bad.isEmpty(), "Identyfikatory z separatorem: ${bad.map { it.id }}")
    }

    @Test
    fun `odznaczone frazy wypadaja z listy do sledzenia`() {
        val all = AdDiscoveryCatalog.phrasesExcept(emptyList())
        assertEquals(AdDiscoveryCatalog.ALL.size, all.size)

        val bezMyjni = AdDiscoveryCatalog.phrasesExcept(listOf("myjnia-bezdotykowa", "myjnia-reczna"))
        assertEquals(AdDiscoveryCatalog.ALL.size - 2, bezMyjni.size)
        assertFalse(bezMyjni.contains("myjnia bezdotykowa"))
        assertTrue(bezMyjni.contains("folia ppf"))
    }

    @Test
    fun `nieznane wykluczenie nie zabiera nic z katalogu`() {
        // Fraza wycofana z katalogu zostawia w czyichś ustawieniach martwy identyfikator.
        // To nie jest błąd i nie ma prawa niczego uciąć.
        val result = AdDiscoveryCatalog.phrasesExcept(listOf("fraza-ktorej-juz-nie-ma"))
        assertEquals(AdDiscoveryCatalog.ALL.size, result.size)
        assertFalse(AdDiscoveryCatalog.exists("fraza-ktorej-juz-nie-ma"))
        assertTrue(AdDiscoveryCatalog.exists("folia-ppf"))
    }

    @Test
    fun `katalog pokrywa glowne uslugi detailingu`() {
        // Zabezpieczenie przed przypadkowym wycięciem całej grupy przy porządkach:
        // każda grupa ma mieć co najmniej jedną frazę, inaczej ekran ustawień
        // pokazałby pustą sekcję.
        AdDiscoveryCatalog.Group.entries.forEach { group ->
            assertTrue(
                AdDiscoveryCatalog.ALL.any { it.group == group },
                "Grupa ${group.name} nie ma ani jednej frazy"
            )
        }
    }
}
