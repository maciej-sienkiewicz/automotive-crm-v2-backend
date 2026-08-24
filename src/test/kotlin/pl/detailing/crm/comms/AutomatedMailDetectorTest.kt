package pl.detailing.crm.comms

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.comms.domain.AutomatedMailDetector

/**
 * Granica między odpowiedzią człowieka a automatem decyduje o „czasie pierwszej
 * reakcji" na leadzie, więc pilnujemy jej testem: fałszywe trafienie kosztuje
 * pominięty stempel, przeoczenie — reakcję zmierzoną w sekundach od autorespondera.
 */
class AutomatedMailDetectorTest {

    @Test
    fun `zwykla odpowiedz pracownika nie jest automatem`() {
        assertFalse(AutomatedMailDetector.isAutomated(emptyMap()))
    }

    @Test
    fun `Auto-Submitted no oznacza wprost czlowieka`() {
        assertFalse(AutomatedMailDetector.isAutomated(mapOf("auto-submitted" to "no")))
    }

    @Test
    fun `autoresponder i wysylka masowa sa automatami`() {
        assertTrue(AutomatedMailDetector.isAutomated(mapOf("auto-submitted" to "auto-replied")))
        assertTrue(AutomatedMailDetector.isAutomated(mapOf("auto-submitted" to "auto-generated")))
        assertTrue(AutomatedMailDetector.isAutomated(mapOf("precedence" to "bulk")))
        assertTrue(AutomatedMailDetector.isAutomated(mapOf("precedence" to "auto_reply")))
        assertTrue(AutomatedMailDetector.isAutomated(mapOf("x-autoreply" to "yes")))
        assertTrue(AutomatedMailDetector.isAutomated(mapOf("x-auto-response-suppress" to "All")))
        assertTrue(AutomatedMailDetector.isAutomated(mapOf("list-id" to "<newsletter.example.com>")))
        assertTrue(AutomatedMailDetector.isAutomated(mapOf("list-unsubscribe" to "<mailto:x@example.com>")))
    }

    @Test
    fun `wielkosc liter i spacje w naglowkach nie maja znaczenia`() {
        assertTrue(AutomatedMailDetector.isAutomated(mapOf("Auto-Submitted" to " Auto-Replied ")))
        assertTrue(AutomatedMailDetector.isAutomated(mapOf("PRECEDENCE" to "Bulk")))
    }

    @Test
    fun `zwykly priorytet wiadomosci nie czyni z niej automatu`() {
        assertFalse(AutomatedMailDetector.isAutomated(mapOf("precedence" to "normal")))
    }
}
