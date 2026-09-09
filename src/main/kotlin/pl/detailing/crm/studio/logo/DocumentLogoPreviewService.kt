package pl.detailing.crm.studio.logo

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import pl.detailing.crm.customer.consent.infrastructure.ConsentTemplateEntity
import pl.detailing.crm.customer.consent.template.DefaultMarketingConsentProvisioner
import pl.detailing.crm.protocol.domain.ProtocolTemplate
import pl.detailing.crm.protocol.domain.ProtocolTemplateFormat
import pl.detailing.crm.protocol.infrastructure.PdfProcessingService
import pl.detailing.crm.protocol.template.DefaultProtocolTemplateProvisioner
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest

/**
 * Podgląd szablonu w ustawieniach „Dokumenty i podpisy" — taki, jaki zobaczy klient.
 *
 * Logo studia nie jest zapisane w kopii szablonu w S3 (trafia do dokumentu dopiero
 * przy wypełnianiu, patrz GenerateVisitProtocolsHandler), więc podpisany link do
 * surowego pliku pokazywałby nagłówek bez logo i użytkownik nie miałby jak sprawdzić,
 * jak wygląda jego dokument. Ten serwis składa podgląd w locie: bajty szablonu + ten
 * sam stempel, którym oznaczamy dokumenty. Szablony niesystemowe wracają bez zmian.
 */
@Service
class DocumentLogoPreviewService(
    private val s3Client: S3Client,
    private val companyLogoService: CompanyLogoService,
    private val pdfProcessingService: PdfProcessingService,
    @Value("\${aws.s3.bucket-name}") private val bucketName: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun protocolTemplatePreview(template: ProtocolTemplate): ByteArray {
        val bytes = download(template.s3Key)
        if (template.fileFormat != ProtocolTemplateFormat.PDF) return bytes
        if (!DefaultProtocolTemplateProvisioner.isSystemTemplate(template)) return bytes
        return withLogo(bytes, template.studioId.value)
    }

    fun consentTemplatePreview(template: ConsentTemplateEntity): ByteArray {
        val bytes = download(template.s3Key)
        if (!DefaultMarketingConsentProvisioner.isSystemTemplate(template)) return bytes
        return withLogo(bytes, template.studioId)
    }

    private fun withLogo(pdf: ByteArray, studioId: java.util.UUID): ByteArray {
        val logo = try {
            companyLogoService.loadDocumentLogo(studioId)
        } catch (e: Exception) {
            logger.warn("Could not load studio logo for preview (studio {}): {}", studioId, e.message)
            null
        } ?: return pdf
        return pdfProcessingService.stampLogoForPreview(pdf, logo.printPng)
    }

    private fun download(key: String): ByteArray =
        s3Client.getObject(GetObjectRequest.builder().bucket(bucketName).key(key).build())
            .use { it.readAllBytes() }
}
