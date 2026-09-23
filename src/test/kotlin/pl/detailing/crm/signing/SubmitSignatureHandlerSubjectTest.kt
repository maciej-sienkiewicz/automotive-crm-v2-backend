package pl.detailing.crm.signing

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.shared.VisitProtocolId
import pl.detailing.crm.signing.domain.SignatureRequest
import pl.detailing.crm.signing.domain.SignatureRequestStatus
import pl.detailing.crm.signing.domain.SignatureSubject
import pl.detailing.crm.signing.infrastructure.DocumentIntegrityService
import pl.detailing.crm.signing.infrastructure.SignatureAuditTrailService
import pl.detailing.crm.signing.infrastructure.SignatureEventPublisher
import pl.detailing.crm.signing.infrastructure.SignatureImageProcessor
import pl.detailing.crm.signing.infrastructure.SignatureRequestEntity
import pl.detailing.crm.signing.infrastructure.SignatureRequestRepository
import pl.detailing.crm.signing.infrastructure.SignedDocumentComposer
import pl.detailing.crm.protocol.infrastructure.VisitProtocolRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.worktime.attendance.AttendanceSheetRemoteSigning
import java.time.Instant
import java.util.Base64
import java.util.Optional
import java.util.UUID

/**
 * Weryfikacja podpisu jest wspólna dla wszystkich dokumentów; to, co dzieje się po niej,
 * zależy od tego, CO podpisano. Lista obecności nie może dotknąć wizyty ani protokołu,
 * a protokół nie może trafić do ścieżki list obecności.
 */
class SubmitSignatureHandlerSubjectTest {

    private val studioId = StudioId.random()
    private val documentBytes = byteArrayOf(4, 5, 6)
    private val sha = "c".repeat(64)

    private val requests = mockk<SignatureRequestRepository>()
    private val visitProtocols = mockk<VisitProtocolRepository>()
    private val visits = mockk<VisitRepository>()
    private val integrity = mockk<DocumentIntegrityService>(relaxed = true)
    private val images = mockk<SignatureImageProcessor>(relaxed = true)
    private val composer = mockk<SignedDocumentComposer>()
    private val auditTrail = mockk<SignatureAuditTrailService>(relaxed = true)
    private val events = mockk<SignatureEventPublisher>(relaxed = true)
    private val attendance = mockk<AttendanceSheetRemoteSigning>()

    private val handler = SubmitSignatureHandler(
        requests, visitProtocols, visits, mockk(), mockk(), integrity, images, composer, auditTrail, events,
        mockk(), mockk(), mockk(), mockk(relaxed = true), mockk(relaxed = true), mockk(), mockk(relaxed = true),
        attendance
    )

    private val saved = mutableListOf<SignatureRequestEntity>()

    init {
        every { requests.save(capture(saved)) } answers { firstArg() }
        every { integrity.consumeChallenge(any(), "challenge") } returns true
        every { integrity.digestsMatch(sha, sha) } returns true
        every { integrity.getCachedDocument(any()) } returns documentBytes
        every { integrity.sha256Hex(documentBytes) } returns sha
        every { images.normalizeToTransparentPng(any()) } returns byteArrayOf(7)
        every { auditTrail.eventsFor(any()) } returns emptyList()
    }

    private fun displayed(subject: SignatureSubject): SignatureRequest =
        signatureRequest(subject, studioId, status = SignatureRequestStatus.DISPLAYED, now = Instant.now())
            .copy(documentSha256 = sha)

    private fun submit(request: SignatureRequest) = runBlocking {
        every { requests.findByIdAndStudioId(request.id.value, studioId.value) } returns SignatureRequestEntity.fromDomain(request)
        handler.handle(
            SubmitSignatureCommand(
                studioId = studioId,
                requestId = request.id,
                tabletId = "tablet-1",
                deviceName = "Recepcja",
                documentSha256 = sha,
                challenge = "challenge",
                declarationAccepted = true,
                declarationAcceptedAt = Instant.now(),
                signatureImageBase64 = Base64.getEncoder().encodeToString(byteArrayOf(1)),
                ipAddress = "10.0.0.7",
                userAgent = "Tablet"
            )
        )
    }

    @Test
    fun `podpis listy obecnosci konczy sie w liscie obecnosci, bez wizyty i protokolu`() {
        val sheetId = UUID.randomUUID()
        val request = displayed(SignatureSubject.AttendanceSheet(sheetId))
        val signedRequest = slot<SignatureRequest>()
        coEvery {
            attendance.completeSigning(capture(signedRequest), sheetId, documentBytes, byteArrayOf(7), any(), emptyList())
        } returns "signed-sheet.pdf"

        val result = submit(request)

        assertEquals(SignatureRequestStatus.COMPLETED, result.status)
        // Karta podpisu dostaje urządzenie i adres, z którego przyszedł podpis.
        assertEquals("10.0.0.7", signedRequest.captured.signerIpAddress)
        assertEquals("Recepcja", signedRequest.captured.signerDevice)
        val completed = saved.last().toDomain()
        assertEquals(SignatureRequestStatus.COMPLETED, completed.status)
        assertEquals("signed-sheet.pdf", completed.signedPdfS3Key)
        verify(exactly = 0) { visits.findById(any()) }
        verify(exactly = 0) { visitProtocols.findByVisitIdAndIdAndStudioId(any(), any(), any()) }
        verify(exactly = 0) { composer.compose(any(), any(), any(), any(), any(), any()) }
        verify {
            events.publish(studioId.value.toString(), request.id.toString(), "SIGNATURE_COMPLETED", "tablet-1", any(), any(), "COMPLETED", any())
        }
    }

    @Test
    fun `podpis protokolu idzie sciezka wizyty, nie listy obecnosci`() {
        val subject = SignatureSubject.VisitProtocol(VisitId.random(), VisitProtocolId.random())
        every { visits.findById(subject.visitId.value) } returns Optional.empty()

        assertThrows<EntityNotFoundException> { submit(displayed(subject)) }

        coVerify(exactly = 0) { attendance.completeSigning(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 1) { visits.findById(subject.visitId.value) }
    }
}
