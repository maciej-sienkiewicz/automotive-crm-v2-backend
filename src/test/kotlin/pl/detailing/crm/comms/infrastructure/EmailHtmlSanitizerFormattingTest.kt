package pl.detailing.crm.comms.infrastructure

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Sanitizer ma CZYŚCIĆ, nie formatować.
 *
 * Zgłoszenie z produkcji: stopka „Mikolaj Błaszczak / CarsLab" po zapisie wracała
 * do edytora jako nazwisko, pusta linia i wcięty „CarsLab". Powód nie był w edytorze,
 * tylko tutaj: Jsoup domyślnie wypisuje dokument z wcięciami, a ten HTML jest potem
 * czytany z powrotem na tekst - więc wcięcia stawały się treścią stopki.
 */
class EmailHtmlSanitizerFormattingTest {

    private val sanitizer = EmailHtmlSanitizer()
    private val messageId = UUID.randomUUID()

    private fun clean(html: String) = sanitizer.sanitize(html, messageId, emptyMap())

    @Test
    fun `stopka nie rozrasta sie o nowe linie ani wciecia`() {
        val result = clean("<div>Mikolaj Błaszczak<br>CarsLab</div>")

        assertEquals("<div>Mikolaj Błaszczak<br>CarsLab</div>", result)
    }

    @Test
    fun `zagniezdzone bloki tez nie dostaja bialych znakow`() {
        val result = clean("<div><p>Pierwszy</p><p>Drugi</p></div>")

        assertFalse(result.contains("\n"), "sanitizer dołożył nowe linie: $result")
        assertFalse(result.contains("  "), "sanitizer dołożył wcięcia: $result")
    }

    @Test
    fun `wiadomosc z white-space pre-wrap zachowuje wlasne wciecia, nie cudze`() {
        val result = clean("<div style=\"white-space:pre-wrap\">Linia\n    wcięta ręcznie</div>")

        // Wcięcie autora zostaje...
        assertTrue(result.contains("    wcięta ręcznie"), result)
        // ...ale nic nie dochodzi wokół znacznika.
        assertFalse(result.startsWith("\n"), result)
    }

    @Test
    fun `czyszczenie nadal dziala - skrypt wypada`() {
        val result = clean("<div>Treść<script>alert(1)</script></div>")

        assertFalse(result.contains("script"), result)
        assertTrue(result.contains("Treść"), result)
    }
}
