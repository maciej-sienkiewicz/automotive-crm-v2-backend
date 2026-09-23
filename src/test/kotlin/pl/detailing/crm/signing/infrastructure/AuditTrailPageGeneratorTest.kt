package pl.detailing.crm.signing.infrastructure

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.shared.VisitProtocolId
import pl.detailing.crm.signing.domain.SignatureSubject
import pl.detailing.crm.signing.signatureRequest
import java.util.UUID

/**
 * Karta podpisu to dowód: ma mówić, CO podpisano, i nie może twierdzić nic, czego system
 * nie zrobił.
 */
class AuditTrailPageGeneratorTest {

    private val generator = AuditTrailPageGenerator()

    private fun auditPageText(subject: SignatureSubject, pageSubject: AuditPageSubject): String =
        PDDocument().use { document ->
            document.addPage(PDPage())
            generator.appendAuditPage(
                document,
                signatureRequest(subject).copy(signerDevice = "Telefon osoby podpisującej (link SMS)"),
                emptyList(),
                pageSubject
            )
            assertEquals(2, document.numberOfPages)
            PDFTextStripper().apply { startPage = 2; endPage = 2 }.getText(document)
        }

    @Test
    fun `karta podpisu listy obecnosci opisuje liste, a nie protokol wizyty`() {
        val sheetId = UUID.randomUUID()
        val text = auditPageText(
            SignatureSubject.AttendanceSheet(sheetId),
            AuditPageSubject("Identyfikator dokumentu (listy obecności)", sheetId.toString(), "Okres rozliczenia", "wrzesień 2026")
        )

        assertTrue("Identyfikator dokumentu (listy obecności)" in text, text)
        assertTrue(sheetId.toString() in text, text)
        assertTrue("Okres rozliczenia" in text && "wrzesień 2026" in text, text)
        assertFalse("Numer wizyty" in text, text)
        assertTrue("Urządzenie podpisujące" in text && "Telefon osoby podpisującej" in text, text)
    }

    @Test
    fun `karta podpisu protokolu dalej niesie numer wizyty`() {
        val protocolId = VisitProtocolId.random()
        val text = auditPageText(
            SignatureSubject.VisitProtocol(VisitId.random(), protocolId),
            AuditPageSubject("Identyfikator dokumentu (protokołu)", protocolId.toString(), "Numer wizyty", "VIS-2026-00042")
        )

        assertTrue("Identyfikator dokumentu (protokołu)" in text, text)
        assertTrue("VIS-2026-00042" in text, text)
    }

    // Pieczętowanie kwalifikowane nigdy nie działało na produkcji i zostało usunięte (V94) -
    // karta, która twierdzi inaczej, jest fałszywym oświadczeniem na dokumencie.
    @Test
    fun `karta podpisu nie twierdzi, ze dokument ma pieczec elektroniczna`() {
        val text = auditPageText(
            SignatureSubject.AttendanceSheet(UUID.randomUUID()),
            AuditPageSubject("Identyfikator dokumentu (listy obecności)", "x", "Okres rozliczenia", "wrzesień 2026")
        )

        assertTrue("Integralna część dokumentu." in text, text)
        assertFalse("pieczęci" in text.lowercase(), text)
        assertFalse("kwalifikowan" in text.lowercase(), text)
    }
}
