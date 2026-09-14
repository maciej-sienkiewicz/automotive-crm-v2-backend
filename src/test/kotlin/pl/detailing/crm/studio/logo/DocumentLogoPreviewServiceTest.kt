package pl.detailing.crm.studio.logo

import io.mockk.every
import io.mockk.mockk
import org.apache.pdfbox.Loader
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.customer.consent.infrastructure.ConsentTemplateEntity
import pl.detailing.crm.customer.consent.template.DefaultMarketingConsentProvisioner
import pl.detailing.crm.protocol.domain.ProtocolTemplate
import pl.detailing.crm.protocol.domain.ProtocolTemplateFormat
import pl.detailing.crm.protocol.domain.ProtocolTemplateVerificationStatus
import pl.detailing.crm.protocol.infrastructure.PdfProcessingService
import pl.detailing.crm.protocol.template.DefaultProtocolTemplateProvisioner
import pl.detailing.crm.shared.ProtocolTemplateId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import software.amazon.awssdk.core.ResponseInputStream
import software.amazon.awssdk.http.AbortableInputStream
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import java.time.Instant
import java.util.UUID

/**
 * „Podgląd" w ustawieniach ma pokazywać to, co dostanie klient. Regresja z produkcji:
 * protokół wydania nie dostawał logo, bo jest zasiany bez `isDefault` — o tym, że
 * szablon jest systemowy, decyduje autor SYSTEM_USER_ID, nie sama flaga.
 */
class DocumentLogoPreviewServiceTest {

    private val studioId = StudioId.random()
    private val s3Client = mockk<S3Client>()
    private val companyLogoService = mockk<CompanyLogoService>()
    private val pdfProcessingService = PdfProcessingService(mockk<S3Client>(), "test-bucket")
    private val service = DocumentLogoPreviewService(s3Client, companyLogoService, pdfProcessingService, "test-bucket")

    private val checkOutPdf = javaClass.getResourceAsStream("/templates/protokol_wydania_pojazdu_default.pdf")!!.readBytes()
    private val logo = DocumentLogo(printPng = LogoTestImages.transparentPngWithBox(1200, 300, 0), vectorSvg = null)

    init {
        every { s3Client.getObject(any<GetObjectRequest>()) } answers {
            ResponseInputStream(GetObjectResponse.builder().build(), AbortableInputStream.create(checkOutPdf.inputStream()))
        }
        every { companyLogoService.loadDocumentLogo(studioId.value) } returns logo
    }

    private fun template(isDefault: Boolean, createdBy: UUID) = ProtocolTemplate(
        id = ProtocolTemplateId.random(),
        studioId = studioId,
        name = "Protokół wydania pojazdu",
        description = null,
        s3Key = "${studioId.value}/protocols/templates/x.pdf",
        fileFormat = ProtocolTemplateFormat.PDF,
        isDefault = isDefault,
        verificationStatus = ProtocolTemplateVerificationStatus.VERIFIED,
        isActive = true,
        createdBy = UserId(createdBy),
        updatedBy = UserId(createdBy),
        createdAt = Instant.now(),
        updatedAt = Instant.now()
    )

    private fun imagesOnFirstPage(pdf: ByteArray): Int = Loader.loadPDF(pdf).use { doc ->
        val res = doc.getPage(0).resources
        res.xObjectNames.count { res.isImageXObject(it) }
    }

    @Test
    fun `systemowy protokol wydania (bez isDefault) dostaje logo w podgladzie`() {
        val systemCheckOut = template(isDefault = false, createdBy = DefaultProtocolTemplateProvisioner.SYSTEM_USER_ID)

        val preview = service.protocolTemplatePreview(systemCheckOut)

        assertEquals(imagesOnFirstPage(checkOutPdf) + 1, imagesOnFirstPage(preview))
    }

    @Test
    fun `wlasny szablon studia wraca bez zmian`() {
        val own = template(isDefault = false, createdBy = UUID.randomUUID())

        assertArrayEquals(checkOutPdf, service.protocolTemplatePreview(own))
    }

    @Test
    fun `wylaczone logo na dokumentach zostawia szablon bez stempla`() {
        every { companyLogoService.loadDocumentLogo(studioId.value) } returns null

        val preview = service.protocolTemplatePreview(template(isDefault = true, createdBy = DefaultProtocolTemplateProvisioner.SYSTEM_USER_ID))

        assertArrayEquals(checkOutPdf, preview)
    }

    @Test
    fun `systemowa zgoda dostaje logo, wersja wgrana przez studio nie`() {
        val system = consentTemplate(createdBy = DefaultMarketingConsentProvisioner.SYSTEM_USER_ID)
        val own = consentTemplate(createdBy = UUID.randomUUID())

        assertEquals(imagesOnFirstPage(checkOutPdf) + 1, imagesOnFirstPage(service.consentTemplatePreview(system)))
        assertArrayEquals(checkOutPdf, service.consentTemplatePreview(own))
    }

    private fun consentTemplate(createdBy: UUID) = ConsentTemplateEntity(
        id = UUID.randomUUID(),
        studioId = studioId.value,
        definitionId = UUID.randomUUID(),
        version = 1,
        s3Key = "${studioId.value}/consents/templates/x.pdf",
        isActive = true,
        requiresResign = false,
        createdBy = createdBy,
        createdAt = Instant.now()
    )
}
