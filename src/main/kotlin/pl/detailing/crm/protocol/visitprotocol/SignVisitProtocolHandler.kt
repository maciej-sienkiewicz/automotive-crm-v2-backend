package pl.detailing.crm.protocol.visitprotocol

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.protocol.domain.VisitProtocol
import pl.detailing.crm.protocol.infrastructure.PdfProcessingService
import pl.detailing.crm.protocol.infrastructure.S3ProtocolStorageService
import pl.detailing.crm.protocol.infrastructure.VisitProtocolEntity
import pl.detailing.crm.protocol.infrastructure.VisitProtocolRepository
import pl.detailing.crm.visit.infrastructure.VisitRepository
import pl.detailing.crm.shared.*

/**
 * Handler for signing a visit protocol.
 *
 * This applies a signature image to the protocol PDF, flattens it to make it immutable,
 * and marks the protocol as SIGNED.
 */
@Service
class SignVisitProtocolHandler(
    private val visitProtocolRepository: VisitProtocolRepository,
    private val visitRepository: VisitRepository,
    private val pdfProcessingService: PdfProcessingService,
    private val s3StorageService: S3ProtocolStorageService
) {

    @Transactional
    suspend fun handle(command: SignVisitProtocolCommand): SignVisitProtocolResult =
        withContext(Dispatchers.IO) {
            // Find the protocol
            val protocolEntity = visitProtocolRepository.findByVisitIdAndIdAndStudioId(
                command.visitId.value,
                command.protocolId.value,
                command.studioId.value
            ) ?: throw NotFoundException("Protocol not found")

            val protocol = protocolEntity.toDomain()

            // Validate protocol can be signed
            if (protocol.status != VisitProtocolStatus.READY_FOR_SIGNATURE) {
                throw ValidationException("Protocol must be in READY_FOR_SIGNATURE status to be signed")
            }

            if (protocol.isImmutable()) {
                throw ValidationException("Protocol is already signed and cannot be modified")
            }

            requireNotNull(protocol.filledPdfS3Key) { "Filled PDF S3 key is missing" }

            // Get visit to retrieve visit number
            val visitEntity = visitRepository.findById(command.visitId.value).orElse(null)
                ?: throw EntityNotFoundException("Visit not found: ${command.visitId}")
            val visitNumber = visitEntity.visitNumber

            // Build S3 keys with visit number and version
            val signedPdfS3Key = s3StorageService.buildSignedPdfS3Key(
                command.studioId.value,
                command.visitId.value,
                visitNumber,
                protocol.version
            )

            // The signature image URL from the frontend is assumed to be already uploaded to S3
            // Extract the S3 key from the URL or assume it's already in the expected location
            val signatureImageS3Key = s3StorageService.buildSignatureImageS3Key(
                command.studioId.value,
                command.visitId.value,
                command.protocolId.value
            )

            // Sign and flatten the PDF
            pdfProcessingService.signAndFlattenPdf(
                pdfS3Key = protocol.filledPdfS3Key,
                signatureImageS3Key = signatureImageS3Key,
                outputS3Key = signedPdfS3Key
            )

            // Update protocol
            val signedProtocol = protocol.sign(
                signedPdfS3Key = signedPdfS3Key,
                signedBy = command.signedBy,
                signatureImageS3Key = signatureImageS3Key,
                notes = command.notes
            )

            val updatedEntity = VisitProtocolEntity.fromDomain(signedProtocol)
            visitProtocolRepository.save(updatedEntity)

            SignVisitProtocolResult(signedProtocol)
        }
}

data class SignVisitProtocolCommand(
    val visitId: VisitId,
    val protocolId: VisitProtocolId,
    val studioId: StudioId,
    val signatureUrl: String,
    val signedBy: String,
    val notes: String?
)

data class SignVisitProtocolResult(
    val protocol: VisitProtocol
)
