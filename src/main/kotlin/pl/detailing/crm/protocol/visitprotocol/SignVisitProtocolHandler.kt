package pl.detailing.crm.protocol.visitprotocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.customer.consent.domain.CustomerConsent
import pl.detailing.crm.customer.consent.infrastructure.ConsentTemplateRepository
import pl.detailing.crm.customer.consent.infrastructure.CustomerConsentEntity
import pl.detailing.crm.customer.consent.infrastructure.CustomerConsentRepository
import pl.detailing.crm.protocol.domain.VisitProtocol
import pl.detailing.crm.protocol.infrastructure.PdfProcessingService
import pl.detailing.crm.protocol.infrastructure.S3ProtocolStorageService
import pl.detailing.crm.protocol.infrastructure.VisitProtocolEntity
import pl.detailing.crm.protocol.infrastructure.VisitProtocolRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.shared.*
import java.time.Instant

/**
 * Signs a visit protocol.
 *
 * For visit-document protocols: applies the signature image to the filled PDF and flattens it.
 * For consent protocols: the consent PDF is served as-is; signing only attaches the signature
 * image and creates an immutable CustomerConsent record so future visits won't re-show this consent.
 */
@Service
class SignVisitProtocolHandler(
    private val visitProtocolRepository: VisitProtocolRepository,
    private val visitRepository: VisitRepository,
    private val pdfProcessingService: PdfProcessingService,
    private val s3StorageService: S3ProtocolStorageService,
    private val consentTemplateRepository: ConsentTemplateRepository,
    private val customerConsentRepository: CustomerConsentRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    suspend fun handle(command: SignVisitProtocolCommand): SignVisitProtocolResult =
        withContext(Dispatchers.IO) {
            val protocolEntity = visitProtocolRepository.findByVisitIdAndIdAndStudioId(
                command.visitId.value, command.protocolId.value, command.studioId.value
            ) ?: throw NotFoundException("Protokół nie został znaleziony")

            val protocol = protocolEntity.toDomain()

            if (protocol.status != VisitProtocolStatus.READY_FOR_SIGNATURE) {
                throw ValidationException("Protokół musi mieć status READY_FOR_SIGNATURE, aby go podpisać")
            }
            if (protocol.isImmutable()) {
                throw ValidationException("Protokół jest już podpisany i nie może być modyfikowany")
            }
            requireNotNull(protocol.filledPdfS3Key) { "Filled PDF S3 key is missing" }

            val visitEntity = visitRepository.findById(command.visitId.value).orElse(null)
                ?: throw EntityNotFoundException("Wizyta nie została znaleziona: ${command.visitId}")
            val visitNumber = visitEntity.visitNumber

            val signedPdfS3Key = s3StorageService.buildSignedPdfS3Key(
                command.studioId.value, command.visitId.value, visitNumber, protocol.version
            )
            val signatureImageS3Key = s3StorageService.buildSignatureImageS3Key(
                command.studioId.value, command.visitId.value, command.protocolId.value
            )

            pdfProcessingService.signAndFlattenPdf(
                pdfS3Key = protocol.filledPdfS3Key,
                signatureImageS3Key = signatureImageS3Key,
                outputS3Key = signedPdfS3Key
            )

            val signedProtocol = protocol.sign(
                signedPdfS3Key = signedPdfS3Key,
                signedBy = command.signedBy,
                signatureImageS3Key = signatureImageS3Key,
                notes = command.notes
            )

            visitProtocolRepository.save(VisitProtocolEntity.fromDomain(signedProtocol))

            // For consent protocols: record the customer's consent so future visits skip this consent
            val consentDefinitionId = protocol.consentDefinitionId
            if (consentDefinitionId != null) {
                recordCustomerConsent(
                    consentDefinitionId = consentDefinitionId,
                    studioId = command.studioId,
                    customerId = CustomerId(visitEntity.customerId),
                    witnessedBy = command.witnessedBy
                )
            }

            SignVisitProtocolResult(signedProtocol)
        }

    private fun recordCustomerConsent(
        consentDefinitionId: ConsentDefinitionId,
        studioId: StudioId,
        customerId: CustomerId,
        witnessedBy: UserId
    ) {
        val activeTemplate = consentTemplateRepository.findActiveByDefinitionIdAndStudioId(
            consentDefinitionId.value, studioId.value
        )
        if (activeTemplate == null) {
            logger.warn(
                "No active consent template for definition {} in studio {} — consent record not created",
                consentDefinitionId.value, studioId.value
            )
            return
        }

        val consent = CustomerConsent(
            id = CustomerConsentId.random(),
            studioId = studioId,
            customerId = customerId,
            templateId = ConsentTemplateId(activeTemplate.id),
            signedAt = Instant.now(),
            witnessedBy = witnessedBy,
            attachmentS3Key = null
        )
        customerConsentRepository.save(CustomerConsentEntity.fromDomain(consent))
    }
}

data class SignVisitProtocolCommand(
    val visitId: VisitId,
    val protocolId: VisitProtocolId,
    val studioId: StudioId,
    val witnessedBy: UserId,
    val signatureUrl: String,
    val signedBy: String,
    val notes: String?
)

data class SignVisitProtocolResult(
    val protocol: VisitProtocol
)
