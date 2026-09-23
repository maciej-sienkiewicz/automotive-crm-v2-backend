package pl.detailing.crm.demo

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Dane przykładowe piaskownicy podglądu roli nie mogą zawierać adresu, pod który coś
 * da się dostarczyć - to druga linia obrony za bezpiecznikiem wysyłek. A zastępniki nie
 * mogą się powtarzać, bo telefon i e-mail klienta są unikalne w studiu.
 */
class SeedContactsTest {

    @Test
    fun `adresy e-mail piaskownicy trafiaja do zarezerwowanej domeny`() {
        assertEquals("jan.kowalski@example.com", SeedContacts.undeliverable().email("jan.kowalski@gmail.com"))
    }

    @Test
    fun `numery piaskownicy nie istnieja w polskiej numeracji i zachowuja koncowke`() {
        assertEquals("+48000345678", SeedContacts.undeliverable().phone("+48 512 345 678"))
    }

    // Dokładnie ten przypadek wywracał zakładanie piaskownicy na produkcji:
    // duplicate key value violates unique constraint "idx_customers_studio_phone".
    @Test
    fun `numery z ta sama koncowka dostaja rozne zastepniki`() {
        val contacts = SeedContacts.undeliverable()

        val substitutes = listOf("+48601234567", "+48501234567", "+48698234567").map(contacts::phone)

        assertEquals(3, substitutes.toSet().size, "Zastępniki się powtarzają: $substitutes")
        assertEquals("+48000234567", substitutes.first())
        assertTrue(substitutes.all { it.startsWith("+48000") && it.length == "+48000234567".length }, "$substitutes")
    }

    @Test
    fun `ten sam numer dostaje ten sam zastepnik niezaleznie od zapisu`() {
        val contacts = SeedContacts.undeliverable()

        val customer = contacts.phone("+48 601 234 567")
        contacts.phone("+48501234567")

        // Lead i SMS w dzienniku wysyłek tego samego klienta muszą wskazywać ten sam numer.
        assertEquals(customer, contacts.phone("+48601234567"))
        assertEquals(customer, contacts.address("+48601234567"))
    }

    @Test
    fun `adresy z ta sama nazwa uzytkownika dostaja rozne zastepniki`() {
        val contacts = SeedContacts.undeliverable()

        val first = contacts.email("biuro@autocars.pl")
        val second = contacts.email("biuro@transpol.pl")

        assertEquals("biuro@example.com", first)
        assertNotEquals(first, second)
        assertTrue(second.endsWith("@example.com"), second)
        assertEquals(first, contacts.address("biuro@autocars.pl"))
    }

    @Test
    fun `puste dane zostaja puste`() {
        val contacts = SeedContacts.undeliverable()

        assertEquals("", contacts.phone(""))
        assertEquals("", contacts.email(""))
    }

    @Test
    fun `kazde zasiewanie przydziela zastepniki od nowa`() {
        SeedContacts.undeliverable().apply {
            phone("+48501234567")
            phone("+48601234567")
        }

        assertEquals("+48000234567", SeedContacts.undeliverable().phone("+48601234567"))
    }

    @Test
    fun `adres z dziennika wysylek jest rozpoznawany po rodzaju`() {
        val contacts = SeedContacts.undeliverable()

        assertEquals("ania@example.com", contacts.address("ania@wp.pl"))
        assertEquals("+48000100200", contacts.address("+48600100200"))
    }

    @Test
    fun `konto demo zachowuje realistyczne dane`() {
        assertEquals("ania@wp.pl", SeedContacts.REALISTIC.address("ania@wp.pl"))
        assertEquals("+48600100200", SeedContacts.REALISTIC.phone("+48600100200"))
    }
}
