package pl.detailing.crm.employee.leaverequest.pdf

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.employee.leave.domain.LeaveType
import pl.detailing.crm.employee.leaverequest.LeaveRequestFixtures
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestOrigin
import pl.detailing.crm.employee.leaverequest.domain.LeaveRequestStatus
import pl.detailing.crm.employee.leaverequest.domain.LeaveSignatureMethod
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.signing.infrastructure.AuditTrailPageGenerator
import pl.detailing.crm.signing.infrastructure.DocumentIntegrityService
import pl.detailing.crm.signing.infrastructure.SignatureImageProcessor
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.visit.infrastructure.DocumentStorageService
import java.time.Instant
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

/**
 * Wniosek wprowadzony w imieniu pracownika na papierze: „Sposób złożenia" mówi, kto go
 * wprowadził, a karta podpisów — że pracownik podpisał osobiście na urządzeniu tej osoby.
 */
class LeaveRequestDocumentServiceTest {

    private val renderer = LeaveRequestPdfRenderer()
    private val stamper = LeaveRequestPdfStamper(renderer)
    private val uploaded = mutableMapOf<String, ByteArray>()
    private val storage = mockk<DocumentStorageService> {
        coEvery { uploadDocument(any(), any(), any(), any()) } answers {
            uploaded[firstArg()] = secondArg()
            firstArg()
        }
    }
    private val settings = mockk<StudioSettingsRepository> { every { findById(any()) } returns Optional.empty() }
    private val service = LeaveRequestDocumentService(
        renderer = renderer,
        stamper = stamper,
        storageService = storage,
        integrity = DocumentIntegrityService(mockk(relaxed = true), mockk(relaxed = true), 15),
        auditTrailPageGenerator = AuditTrailPageGenerator(),
        studioSettingsRepository = settings,
        companyLogoService = mockk { every { loadDocumentLogo(any()) } returns null }
    )
    private val signature = SignatureImageProcessor().normalizeToTransparentPng(LeaveRequestFixtures.signaturePng())
    private val studio = StudioId.random()

    private fun text(pdf: ByteArray): String = Loader.loadPDF(pdf).use { PDFTextStripper().getText(it) }

    private fun draft(mode: String) = runBlocking {
        service.createDraft(
            studio, UUID.randomUUID(),
            LeaveRequestDocumentService.DraftContent(
                number = "WU/2026/0013",
                submissionMode = mode,
                employeeName = "Jan Kowalski",
                employeeEmail = null,
                employeePhone = null,
                startDate = LocalDate.of(2026, 11, 3),
                endDate = LocalDate.of(2026, 11, 5),
                workingDays = 3,
                leaveType = LeaveType.ANNUAL,
                onDemand = false,
                reason = null
            )
        )
    }

    @Test
    fun `submission mode names who entered the request and that the employee signs in person`() {
        val mode = LeaveRequestDocumentService.submissionMode(LeaveRequestOrigin.ON_BEHALF, "Anna Nowak")
        assertEquals("Wprowadzony przez: Anna Nowak, podpisany osobiście", mode)
        assertEquals(
            LeaveRequestDocumentService.SUBMISSION_MODE,
            LeaveRequestDocumentService.submissionMode(LeaveRequestOrigin.SELF_SERVICE, "Jan Kowalski")
        )

        val text = text(draft(mode).bytes).replace("\n", " ")
        assertTrue(text.contains("Wprowadzony przez: Anna Nowak, podpisany osobiście"), text)
    }

    @Test
    fun `long author name wraps inside the submission mode field instead of being cut`() {
        val mode = LeaveRequestDocumentService.submissionMode(
            LeaveRequestOrigin.ON_BEHALF, "Aleksandra Maria Wiśniewska-Szczepańska"
        )
        val pdf = draft(mode).bytes
        assertEquals(1, Loader.loadPDF(pdf).use { it.numberOfPages })
        val text = text(pdf).replace("\n", " ")
        assertTrue(text.contains("Wiśniewska-Szczepańska, podpisany osobiście"), text)
    }

    @Test
    fun `signature card states that the employee signed in person on the device of the author`() {
        val pending = LeaveRequestFixtures.request(
            studio, UUID.randomUUID(), null, status = LeaveRequestStatus.PENDING,
            origin = LeaveRequestOrigin.ON_BEHALF, createdBy = UserId.random().value, createdByName = "Anna Nowak",
            employeeSignatureMethod = LeaveSignatureMethod.IN_PERSON
        )
        val employeeSigned = stamper.stampEmployeeSignature(
            draft(LeaveRequestDocumentService.submissionMode(LeaveRequestOrigin.ON_BEHALF, "Anna Nowak")).bytes,
            signature, Instant.now()
        )

        val final = runBlocking {
            service.storeFinal(
                pending, "Jan Kowalski", employeeSigned, signature,
                LeaveRequestDocumentService.DecisionStamp(
                    approved = true, decidedByName = "Anna Nowak", note = null,
                    method = LeaveSignatureMethod.DEVICE_DRAWN, decidedAt = Instant.now(),
                    ipAddress = "10.0.0.20", userAgent = "Mozilla/5.0 (iPad)"
                )
            )
        }

        val card = text(final.bytes).replace("\n", " ")
        assertTrue(card.contains("złożony osobiście na ekranie urządzenia osoby wprowadzającej wniosek (Anna Nowak)"), card)
        assertTrue(card.contains("Osoba wprowadzająca wniosek"), card)
        assertTrue(card.contains("Anna Nowak, w imieniu pracownika"), card)
        assertFalse(card.contains("Podstawa uprawnienia"), card)
    }
}
