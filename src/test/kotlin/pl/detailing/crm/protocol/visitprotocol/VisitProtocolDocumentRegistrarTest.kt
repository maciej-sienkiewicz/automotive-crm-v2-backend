package pl.detailing.crm.protocol.visitprotocol

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.protocol.domain.VisitProtocol
import pl.detailing.crm.protocol.infrastructure.CrmDataResolver
import pl.detailing.crm.shared.CrmDataKey
import pl.detailing.crm.shared.DocumentType
import pl.detailing.crm.shared.ProtocolStage
import pl.detailing.crm.shared.ProtocolTemplateId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.VisitId
import pl.detailing.crm.shared.VisitProtocolId
import pl.detailing.crm.shared.VisitProtocolStatus
import pl.detailing.crm.visit.infrastructure.DocumentService
import pl.detailing.crm.visit.infrastructure.VisitEntity
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Kiedy protokół staje się dokumentem wizyty.
 *
 * Zgłoszenie z warsztatu: „Wydaj pojazd" → „Pomiń podpis i przejdź do płatności" →
 * zamknięcie okna zostawiało w dokumentach `08-09-2026_Mercedes-benz_Klasa-S_Lux_wydanie`.
 * Nikt tego pliku nie zamówił — powstał dlatego, że ekran został otwarty.
 */
class VisitProtocolDocumentRegistrarTest {

    private val visitRepository: VisitRepository = mockk()
    private val crmDataResolver: CrmDataResolver = mockk()
    private val documentService: DocumentService = mockk(relaxed = true)

    private val registrar = VisitProtocolDocumentRegistrar(visitRepository, crmDataResolver, documentService)

    private val studioId = StudioId.random()
    private val visitId = VisitId.random()

    private fun protocol(stage: ProtocolStage, version: Int = 1) = VisitProtocol(
        id = VisitProtocolId.random(), studioId = studioId, visitId = visitId,
        templateId = ProtocolTemplateId(UUID.randomUUID()), consentTemplateId = null,
        stage = stage, version = version, status = VisitProtocolStatus.READY_FOR_SIGNATURE,
        consentDefinitionId = null, filledPdfS3Key = "filled.pdf", signedPdfS3Key = null,
        signedAt = null, signedBy = null, signatureImageS3Key = null, notes = null,
        createdAt = Instant.now(), updatedAt = Instant.now()
    )

    private fun givenVisit() {
        every { visitRepository.findById(visitId.value) } returns Optional.of(
            mockk<VisitEntity>(relaxed = true).also {
                every { it.brandSnapshot } returns "Mercedes-Benz"
                every { it.modelSnapshot } returns "Klasa S"
                every { it.visitNumber } returns "WIZ/2026/09/001"
                every { it.customerId } returns UUID.randomUUID()
                every { it.createdBy } returns UUID.randomUUID()
            }
        )
        coEvery { crmDataResolver.resolveVisitData(visitId, studioId) } returns
            mapOf(CrmDataKey.CUSTOMER_FULL_NAME to "Jan Lux")
    }

    // ── Reguła ───────────────────────────────────────────────────────────────

    @Test
    fun `protokol wydania NIE jest dokumentem w chwili wygenerowania`() {
        assertFalse(VisitProtocolDocumentRegistrar.becomesDocumentOnGeneration(ProtocolStage.CHECK_OUT))
    }

    @Test
    fun `protokol przyjecia jest dokumentem od razu - opisuje stan auta, nie zgodę klienta`() {
        assertTrue(VisitProtocolDocumentRegistrar.becomesDocumentOnGeneration(ProtocolStage.CHECK_IN))
    }

    // ── Rejestracja ──────────────────────────────────────────────────────────

    @Test
    fun `zarejestrowany protokol wydania niesie nazwe, po ktorej czlowiek go znajdzie`() = runBlocking {
        givenVisit()
        val name = slot<String>()
        val fileName = slot<String>()

        registrar.register(protocol(ProtocolStage.CHECK_OUT), "signed.pdf", "pdf")

        coVerify {
            documentService.registerDocument(
                visitId = visitId.value, customerId = any(), documentType = DocumentType.PROTOCOL,
                name = capture(name), s3Key = "signed.pdf", fileName = capture(fileName),
                createdBy = any(), createdByName = any(), category = "protocol"
            )
        }
        assertTrue(name.captured.endsWith("_Mercedes-Benz_Klasa-S_Lux_wydanie"), name.captured)
        assertEquals("${name.captured}.pdf", fileName.captured)
    }

    @Test
    fun `protokol przyjecia dostaje wlasna koncowke nazwy`() = runBlocking {
        givenVisit()
        val name = slot<String>()

        registrar.register(protocol(ProtocolStage.CHECK_IN), "filled.pdf", "pdf")

        coVerify { documentService.registerDocument(any(), any(), any(), capture(name), any(), any(), any(), any(), any()) }
        assertTrue(name.captured.endsWith("_przyjecie"), name.captured)
    }

    @Test
    fun `kolejna wersja protokolu nie udaje tego samego dokumentu`() = runBlocking {
        givenVisit()
        val name = slot<String>()

        registrar.register(protocol(ProtocolStage.CHECK_OUT, version = 2), "signed.pdf", "pdf")

        coVerify { documentService.registerDocument(any(), any(), any(), capture(name), any(), any(), any(), any(), any()) }
        assertTrue(name.captured.endsWith("_wydanie_v2"), name.captured)
    }

    @Test
    fun `awaria zapisu dokumentu nie wywraca operacji, w ktorej powstal`() = runBlocking {
        givenVisit()
        coEvery {
            documentService.registerDocument(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } throws IllegalStateException("S3 down")

        registrar.register(protocol(ProtocolStage.CHECK_OUT), "signed.pdf", "pdf")
    }

    @Test
    fun `brak wizyty konczy sie ostrzezeniem, nie wyjatkiem`() = runBlocking {
        every { visitRepository.findById(visitId.value) } returns Optional.empty()

        registrar.register(protocol(ProtocolStage.CHECK_OUT), "signed.pdf", "pdf")

        coVerify(exactly = 0) {
            documentService.registerDocument(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }
}
