package pl.detailing.crm.comms.signature

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.shared.ValidationException

/**
 * Projekt stopki wraca do kreatora przy każdym otwarciu, a jego linki i obrazki lądują
 * w cudzych skrzynkach — zapis ma odrzucić to, czego kreator nie odtworzy albo co nie
 * zadziała u odbiorcy, zamiast zapisać to po cichu.
 */
class MailSignatureDesignTest {

    private fun design(
        template: String = "klasyczna",
        fullName: String? = "Anna Kowalska",
        color: String = "#C0272D"
    ) = MailSignatureDesign(template = template, fullName = fullName, color = color)

    @Test
    fun `przycina pola, puste zamienia na null, kolor zapisuje malymi literami`() {
        val normalized = design().copy(
            position = "  Kierownik   studia ",
            company = "   ",
            website = " www.studio.pl "
        ).normalized()

        assertEquals("Kierownik studia", normalized.position)
        assertNull(normalized.company)
        assertEquals("www.studio.pl", normalized.website)
        assertEquals("#c0272d", normalized.color)
    }

    @Test
    fun `przyjmuje wszystkie piec motywow`() {
        listOf("klasyczna", "ze-zdjeciem", "firmowa", "baner-okrag", "dwa-pasma").forEach {
            assertEquals(it, design(template = it).normalized().template)
        }
    }

    @Test
    fun `odrzuca nieznany motyw, zly kolor, czcionke, wielkosc i styl ikon`() {
        assertThrows<ValidationException> { design(template = "ekspert").normalized() }
        assertThrows<ValidationException> { design(color = "red").normalized() }
        assertThrows<ValidationException> { design().copy(font = "comic-sans").normalized() }
        assertThrows<ValidationException> { design().copy(size = "xl").normalized() }
        assertThrows<ValidationException> { design().copy(iconStyle = "neon").normalized() }
    }

    @Test
    fun `imie i nazwisko jest wymagane`() {
        assertThrows<ValidationException> { design(fullName = "  ").normalized() }
    }

    @Test
    fun `link bez schematu i z portem przechodzi, javascript nie`() {
        assertEquals("firma.pl:8080", design().copy(website = "firma.pl:8080").normalized().website)
        assertEquals(
            "https://linkedin.com/in/anna",
            design().copy(linkedin = "https://linkedin.com/in/anna").normalized().linkedin
        )
        assertThrows<ValidationException> { design().copy(facebook = "javascript:alert(1)").normalized() }
        assertThrows<ValidationException> { design().copy(website = "data:text/html,x").normalized() }
        assertThrows<ValidationException> { design().copy(instagram = "instagram.com/a b").normalized() }
    }

    @Test
    fun `obrazek musi miec absolutny adres https`() {
        val url = "https://api.detailboost.pl/api/public/mail-signature/x/0123456789abcdef.jpg"
        assertEquals(url, design().copy(photoUrl = url).normalized().photoUrl)

        assertThrows<ValidationException> { design().copy(photoUrl = "http://firma.pl/foto.jpg").normalized() }
        // Lokalnie aplikacja chodzi po http, a adres obrazka to jej domena.
        val local = "http://localhost:5173/api/public/mail-signature/x/0123456789abcdef.jpg"
        assertEquals(local, design().copy(photoUrl = local).normalized().photoUrl)
        assertThrows<ValidationException> { design().copy(photoUrl = "http://localhost.evil.pl/x.jpg").normalized() }
        assertThrows<ValidationException> { design().copy(logoUrl = "/api/public/branding/logo.png").normalized() }
        assertThrows<ValidationException> { design().copy(logoUrl = "data:image/png;base64,AAAA").normalized() }
    }

    @Test
    fun `za dlugie pola sa odrzucane z nazwa pola`() {
        val error = assertThrows<ValidationException> {
            design().copy(position = "x".repeat(MailSignatureDesign.MAX_LINE + 1)).normalized()
        }
        assertEquals(true, error.message!!.startsWith("Stanowisko"))
        assertThrows<ValidationException> {
            design().copy(disclaimer = "x".repeat(MailSignatureDesign.MAX_DISCLAIMER + 1)).normalized()
        }
    }

    @Test
    fun `stopka prawna zachowuje podzial na linie`() {
        val normalized = design().copy(disclaimer = "Linia pierwsza\nLinia druga\u0007").normalized()
        assertEquals("Linia pierwsza\nLinia druga", normalized.disclaimer)
    }
}
