package pl.detailing.crm.ksef.auth

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.akmf.ksef.sdk.client.interfaces.KSeFClient
import pl.akmf.ksef.sdk.client.model.auth.AuthStatusResponse
import pl.akmf.ksef.sdk.client.model.auth.StatusInfo

/**
 * Status uwierzytelnienia KSeF (`GET /auth/{referenceNumber}`) ma jeden kod „w toku" (100)
 * i jeden sukces (200). Wszystko inne to werdykt terminalny — wcześniej pętla traktowała
 * np. 450 (błędny token) jak „w toku" i odpytywała KSeF 20 razy, zjadając limit żądań
 * kontekstu NIP, a na koniec zgłaszała mylący timeout.
 */
class KsefAuthServicePollingTest {

    private val ksefClient = mockk<KSeFClient>()
    private val service = KsefAuthService(ksefClient, mockk(), mockk(), mockk()).apply {
        pollSleeper = {}
    }

    private fun status(code: Int?, description: String? = null, details: List<String>? = null): AuthStatusResponse {
        val info = mockk<StatusInfo> {
            every { this@mockk.code } returns code
            every { this@mockk.description } returns description
            every { this@mockk.details } returns details
        }
        return mockk { every { status } returns info }
    }

    private fun givenStatuses(vararg responses: AuthStatusResponse) {
        every { ksefClient.getAuthStatus(REF, TEMP) } returnsMany responses.toList()
    }

    @Test
    fun `450 przerywa odpytywanie po pierwszej odpowiedzi`() {
        givenStatuses(status(450, "Uwierzytelnianie zakończone niepowodzeniem z powodu błędnego tokenu", listOf("Token unieważniony")))

        val e = assertThrows<KsefAuthException> { service.waitForAuthReady(REF, TEMP) }

        assertEquals(450, e.statusCode)
        assertEquals(REF, e.referenceNumber)
        assertTrue(e.message!!.contains("kod 450"))
        assertTrue(e.message!!.contains("Token unieważniony"), "szczegóły z KSeF muszą zostać w komunikacie")
        verify(exactly = 1) { ksefClient.getAuthStatus(REF, TEMP) }
    }

    @Test
    fun `kod w toku jest odpytywany dalej az do sukcesu`() {
        givenStatuses(status(100), status(100), status(200))

        service.waitForAuthReady(REF, TEMP)

        verify(exactly = 3) { ksefClient.getAuthStatus(REF, TEMP) }
    }

    @Test
    fun `blad po kilku odpowiedziach w toku jest terminalny`() {
        givenStatuses(status(100), status(400, "Uwierzytelnianie zakończone niepowodzeniem"))

        val e = assertThrows<KsefAuthException> { service.waitForAuthReady(REF, TEMP) }

        assertEquals(400, e.statusCode)
        verify(exactly = 2) { ksefClient.getAuthStatus(REF, TEMP) }
    }

    @Test
    fun `kod 5xx tez jest terminalny`() {
        givenStatuses(status(500, "Nieznany błąd"))

        val e = assertThrows<KsefAuthException> { service.waitForAuthReady(REF, TEMP) }

        assertEquals(500, e.statusCode)
        verify(exactly = 1) { ksefClient.getAuthStatus(REF, TEMP) }
    }

    @Test
    fun `nieznany kod spoza kontraktu nie jest traktowany jak w toku`() {
        givenStatuses(status(315))

        val e = assertThrows<KsefAuthException> { service.waitForAuthReady(REF, TEMP) }

        assertEquals(315, e.statusCode)
        assertTrue(e.message!!.contains("nieoczekiwany"))
    }

    @Test
    fun `brak kodu to brak werdyktu - odpytujemy dalej`() {
        givenStatuses(status(null), status(200))

        service.waitForAuthReady(REF, TEMP)

        verify(exactly = 2) { ksefClient.getAuthStatus(REF, TEMP) }
    }

    @Test
    fun `wyczerpanie prob bez werdyktu konczy sie wyjatkiem bez kodu`() {
        every { ksefClient.getAuthStatus(REF, TEMP) } returns status(100)

        val e = assertThrows<KsefAuthException> { service.waitForAuthReady(REF, TEMP) }

        assertNull(e.statusCode)
        verify(exactly = 20) { ksefClient.getAuthStatus(REF, TEMP) }
    }

    @Test
    fun `komunikat 450 mowi wlascicielowi co sprawdzic`() {
        val message = service.describeAuthFailure(450, "Uwierzytelnianie zakończone niepowodzeniem z powodu błędnego tokenu", null)

        assertTrue(message.contains("środowisku KSeF"))
        assertTrue(message.contains("NIP"))
    }

    @Test
    fun `komunikat 415 wskazuje brak uprawnien`() {
        val message = service.describeAuthFailure(415, "Brak przypisanych uprawnień", emptyList())

        assertTrue(message.contains("uprawnień"))
        assertTrue(message.contains("InvoiceRead"))
    }

    @Test
    fun `brak opisu z KSeF nie psuje komunikatu`() {
        val message = service.describeAuthFailure(470, null, null)

        assertTrue(message.contains("kod 470"))
        assertTrue(message.contains("brak opisu"))
    }

    private companion object {
        const val REF = "20260922-AU-3C0D676000-3C1B0F4BAD-77"
        const val TEMP = "temp-token"
    }
}
