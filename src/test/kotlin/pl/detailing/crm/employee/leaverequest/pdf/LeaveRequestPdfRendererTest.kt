package pl.detailing.crm.employee.leaverequest.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.employee.leave.domain.LeaveType
import pl.detailing.crm.employee.leaverequest.LeaveRequestFixtures
import pl.detailing.crm.shared.pdf.DocumentStyle
import pl.detailing.crm.signing.infrastructure.AuditTrailPageGenerator
import pl.detailing.crm.signing.infrastructure.SignatureCardEntry
import pl.detailing.crm.signing.infrastructure.SignatureImageProcessor
import java.time.Instant
import java.time.LocalDate

/**
 * Wniosek rysowany w kodzie: mieści się na jednej stronie, stemple trafiają w dokument
 * bez błędu, każdy stempel zwraca nowy plik, a karta podpisów dokłada dokładnie jedną stronę.
 */
class LeaveRequestPdfRendererTest {

    private val renderer = LeaveRequestPdfRenderer()
    private val stamper = LeaveRequestPdfStamper(renderer)
    private val signature = SignatureImageProcessor().normalizeToTransparentPng(LeaveRequestFixtures.signaturePng())

    private fun data(reason: String? = "Wyjazd rodzinny", leaveType: LeaveType = LeaveType.ANNUAL, onDemand: Boolean = false) =
        LeaveRequestPdfData(
            number = "WU/2026/0012",
            submissionMode = LeaveRequestDocumentService.SUBMISSION_MODE,
            employerName = "Detailing Studio Łódź sp. z o.o.",
            employerAddress = "ul. Piotrkowska 1, 90-001 Łódź",
            employerTaxId = "7251234567",
            logoPng = null,
            employeeName = "Zażółć Gęślą-Jaźń",
            employeeEmail = "jan@studio.pl",
            employeePhone = "+48 600 100 200",
            startDate = LocalDate.of(2026, 11, 3),
            endDate = LocalDate.of(2026, 11, 7),
            workingDays = 4,
            leaveType = leaveType,
            onDemand = onDemand,
            reason = reason,
            substituteName = "Tomasz Wiśniewski"
        )

    private fun text(pdf: ByteArray): String = Loader.loadPDF(pdf).use { PDFTextStripper().getText(it) }
    private fun pages(pdf: ByteArray): Int = Loader.loadPDF(pdf).use { it.numberOfPages }

    @Test
    fun `renders one page with every section of the template`() {
        val pdf = renderer.render(data())

        assertEquals(1, pages(pdf))
        val text = text(pdf)
        listOf(
            "PRACODAWCA", "WNIOSEK URLOPOWY", "NR WNIOSKU", "DATA I GODZINA ZŁOŻENIA", "SPOSÓB ZŁOŻENIA",
            "PRACOWNIK", "TERMIN URLOPU", "RODZAJ URLOPU", "UZASADNIENIE WNIOSKU", "OSOBA ZASTĘPUJĄCA",
            "OŚWIADCZENIA PRACOWNIKA", "PODPIS PRACOWNIKA", "DECYZJA PRACODAWCY", "Osoba rozpatrująca",
            "Podstawa uprawnienia", "UZASADNIENIE DECYZJI", "PODPIS OSOBY ROZPATRUJĄCEJ",
            "WU/2026/0012", "Zażółć Gęślą-Jaźń", "03.11.2026", "Tomasz Wiśniewski", "NIP 7251234567"
        ).forEach { assertTrue(text.contains(it), "Brak na wniosku: $it") }
    }

    @Test
    fun `content stays above the footnote`() {
        val layout = renderer.layout
        assertTrue(layout.contentBottom > layout.footnoteTop + 2f) {
            "Treść (${layout.contentBottom}) wchodzi na przypis (${layout.footnoteTop})"
        }
        assertTrue(layout.submittedAt.top < DocumentStyle.PAGE_H)
    }

    @Test
    fun `longest allowed reason still renders on one page`() {
        val pdf = renderer.render(data(reason = "x".repeat(10) + " Ślub brata ".repeat(40).take(LeaveRequestPdfRenderer.MAX_REASON_LENGTH - 10)))
        assertEquals(1, pages(pdf))
    }

    @Test
    fun `employee stamp returns a new file and leaves the draft untouched`() {
        val draft = renderer.render(data())
        val draftCopy = draft.copyOf()

        val signed = stamper.stampEmployeeSignature(draft, signature, Instant.parse("2026-09-29T16:42:05Z"))

        assertArrayEquals(draftCopy, draft)
        assertFalse(draft.contentEquals(signed))
        assertEquals(1, pages(signed))
        assertTrue(text(signed).contains("29.09.2026, 18:42:05"), "Chwila złożenia w czasie Europe/Warsaw")
    }

    @Test
    fun `decision stamp with signature card adds exactly one page`() {
        val signed = stamper.stampEmployeeSignature(renderer.render(data()), signature, Instant.now())
        val before = pages(signed)

        val final = stamper.stampDecision(
            pdf = signed,
            approved = true,
            decidedByName = "Anna Nowak",
            basisText = "Uprawnienie: Akceptacja wniosków urlopowych, rola: Kierownik zmiany",
            note = "Zgoda, zastępstwo ustalone.",
            normalizedSignaturePng = signature,
            decidedAt = Instant.now()
        ) { document ->
            AuditTrailPageGenerator().appendSignatureCard(
                document = document,
                documentName = "Wniosek urlopowy WU/2026/0012",
                identification = listOf("Numer wniosku" to "WU/2026/0012"),
                signatures = listOf(
                    SignatureCardEntry("pracownik", "Jan Kowalski", "podpis odręczny", Instant.now(), "10.0.0.1", "Mozilla/5.0", "a".repeat(64), "wniosek bez podpisów", "Znam treść wniosku i podpisuję go."),
                    SignatureCardEntry("rozpatrujący", "Anna Nowak", "zapisany podpis", Instant.now(), null, null, "b".repeat(64), "wniosek z podpisem pracownika")
                ),
                integrityNote = "Test."
            )
        }

        assertEquals(before + 1, pages(final))
        val text = text(final)
        assertTrue(text.contains("Anna Nowak"))
        assertTrue(text.contains("rola: Kierownik zmiany"))
        assertTrue(text.contains("KARTA PODPISÓW"))
        assertTrue(text.contains("b".repeat(64)), "Karta niesie skrót dokumentu podpisanego przez rozpatrującego")
    }

    @Test
    fun `rejection stamp renders the note`() {
        val signed = stamper.stampEmployeeSignature(renderer.render(data(leaveType = LeaveType.ANNUAL, onDemand = true)), signature, Instant.now())
        val final = stamper.stampDecision(signed, false, "Właściciel", "Właściciel studia", "Brak obsady w tym terminie", signature, Instant.now())
        assertEquals(1, pages(final))
        assertTrue(text(final).contains("Brak obsady w tym terminie"))
    }
}
