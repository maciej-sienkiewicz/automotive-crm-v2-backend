package pl.detailing.crm.leads

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import pl.detailing.crm.leads.formmail.ExtractedFormLead

/**
 * Kontakt odczytany przez model musi stać w treści dosłownie. Wymyślony adres to
 * wycena wysłana obcej osobie — zakaz zgadywania w prompcie nie jest gwarancją.
 */
class FormExtractionGroundingTest {

    private val body = """
        Formularz kontaktowy - carslab.pl/kontakt

        Imię: Paulina Riffey
        Email: pw.riffey@gmail.com
        Samochód: 2023 Ford Explorer Timberline

        Treść wiadomości:
        Mój nr telefonu do kontaktu 511 038 420.
    """.trimIndent()

    private fun extracted(email: String?, phone: String?) = ExtractedFormLead(
        customerName = "Paulina Riffey",
        email = email,
        phone = phone,
        message = null,
        service = null,
        vehicleBrand = null,
        vehicleModel = null
    )

    @Test
    fun `adres i telefon z tresci zostaja`() {
        val grounded = extracted("pw.riffey@gmail.com", "+48 511038420").groundedIn(body)

        assertEquals("pw.riffey@gmail.com", grounded.email)
        assertEquals("+48 511038420", grounded.phone)
    }

    @Test
    fun `wymyslony adres znika z wyniku`() {
        val grounded = extracted("paulina.riffey@gmail.com", null).groundedIn(body)

        assertNull(grounded.email)
    }

    @Test
    fun `wymyslony telefon znika z wyniku`() {
        val grounded = extracted(null, "600 100 200").groundedIn(body)

        assertNull(grounded.phone)
    }
}
