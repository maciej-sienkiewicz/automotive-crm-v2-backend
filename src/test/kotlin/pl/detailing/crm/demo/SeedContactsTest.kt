package pl.detailing.crm.demo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Dane przykładowe piaskownicy podglądu roli nie mogą zawierać adresu, pod który coś
 * da się dostarczyć - to druga linia obrony za bezpiecznikiem wysyłek.
 */
class SeedContactsTest {

    @Test
    fun `adresy e-mail piaskownicy trafiaja do zarezerwowanej domeny`() {
        assertEquals("jan.kowalski@example.com", SeedContacts.UNDELIVERABLE.email("jan.kowalski@gmail.com"))
    }

    @Test
    fun `numery piaskownicy nie istnieja w polskiej numeracji i dalej sie od siebie roznia`() {
        val first = SeedContacts.UNDELIVERABLE.phone("+48 512 345 678")
        val second = SeedContacts.UNDELIVERABLE.phone("+48 512 700 800")

        assertEquals("+48000345678", first)
        assertTrue(first.startsWith("+48000"))
        assertNotEquals(first, second)
    }

    @Test
    fun `adres z dziennika wysylek jest rozpoznawany po rodzaju`() {
        assertEquals("ania@example.com", SeedContacts.UNDELIVERABLE.address("ania@wp.pl"))
        assertEquals("+48000100200", SeedContacts.UNDELIVERABLE.address("+48600100200"))
    }

    @Test
    fun `konto demo zachowuje realistyczne dane`() {
        assertEquals("ania@wp.pl", SeedContacts.REALISTIC.address("ania@wp.pl"))
        assertEquals("+48600100200", SeedContacts.REALISTIC.phone("+48600100200"))
    }
}
