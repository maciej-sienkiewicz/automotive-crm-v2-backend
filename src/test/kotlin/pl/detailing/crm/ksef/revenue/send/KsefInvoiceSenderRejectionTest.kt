package pl.detailing.crm.ksef.revenue.send

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.akmf.ksef.sdk.client.interfaces.CryptographyService
import pl.akmf.ksef.sdk.client.interfaces.KSeFClient
import pl.detailing.crm.ksef.auth.KsefAuthService

/**
 * Treść komunikatu odrzucenia trafia wprost do `last_send_error` i na frontend,
 * więc dla kodu 440 („Duplikat faktury") musi mówić, co zrobić — surowy opis
 * z KSeF podaje tylko numer KSeF cudzego dokumentu.
 */
class KsefInvoiceSenderRejectionTest {

    private val sender = KsefInvoiceSender(
        mockk<KSeFClient>(), mockk<CryptographyService>(), mockk<KsefAuthService>()
    )

    @Test
    fun `kod 440 dostaje wyjasnienie i instrukcje ponownego wystawienia`() {
        val message = sender.describeRejection(
            code = 440,
            description = "Duplikat faktury",
            details = listOf(
                "Faktura o numerze KSeF: 1238394445-20260814-82957C800000-3F " +
                    "została już prawidłowo przesłana do systemu"
            )
        )

        assertTrue(message.contains("duplikat", ignoreCase = true))
        assertTrue(message.contains("440"))
        assertTrue(message.contains("Wystaw dokument ponownie"))
        // Oryginalna treść z KSeF zostaje — bez niej nie da się odnaleźć kolidującego dokumentu
        assertTrue(message.contains("1238394445-20260814-82957C800000-3F"))
    }

    @Test
    fun `pozostale kody zachowuja dotychczasowy format`() {
        val message = sender.describeRejection(
            code = 450,
            description = "Błąd weryfikacji semantyki dokumentu faktury",
            details = listOf("Podmiot2: niekompletna zawartość")
        )

        assertTrue(message.startsWith("KSeF odrzucił fakturę (kod 450)"))
        assertTrue(message.contains("Podmiot2"))
        assertFalse(message.contains("Wystaw dokument ponownie"))
    }

    @Test
    fun `brak szczegolow nie psuje komunikatu`() {
        val message = sender.describeRejection(code = 415, description = "Nieobsługiwany format", details = null)
        assertTrue(message.contains("Nieobsługiwany format"))
    }
}
