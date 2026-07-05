package pl.detailing.crm.signing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.customer.consent.infrastructure.ConsentDefinitionRepository
import pl.detailing.crm.protocol.infrastructure.ProtocolTemplateRepository
import pl.detailing.crm.protocol.infrastructure.S3ProtocolStorageService
import pl.detailing.crm.protocol.infrastructure.VisitProtocolRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.signing.domain.SignatureAuditEventType
import pl.detailing.crm.signing.domain.SignatureRequest
import pl.detailing.crm.signing.domain.SignatureRequestStatus
import pl.detailing.crm.signing.infrastructure.*
import java.time.Instant

/**
 * Handles the "Poproś o podpis" action from the CRM.
 *
 * WYSIWYS anchor: the handler downloads the EXACT filled PDF that will be shown on the
 * tablet and computes its SHA-256 digest. From this moment the signature can only be
 * bound to a document whose bytes hash to this value — any substitution (a different
 * template revision, a re-generated PDF, a tampered S3 object) aborts the session.
 */
@Service
class RequestSignatureHandler(
    private val visitProtocolRepository: VisitProtocolRepository,
    private val visitRepository: pl.detailing.crm.visit.infrastructure.VisitRepository,
    private val protocolTemplateRepository: ProtocolTemplateRepository,
    private val consentDefinitionRepository: ConsentDefinitionRepository,
    private val signatureRequestRepository: SignatureRequestRepository,
    private val s3StorageService: S3ProtocolStorageService,
    private val documentIntegrityService: DocumentIntegrityService,
    private val auditTrailService: SignatureAuditTrailService,
    private val eventPublisher: SignatureEventPublisher,
    @Value("\${signing.request.ttl-minutes:15}") private val requestTtlMinutes: Long,
    @Value("\${signing.request.default-declaration:Oświadczam, że zapoznałem/zapoznałam się z treścią niniejszego dokumentu, rozumiem jego treść i akceptuję zawarte w nim ustalenia.}")
    private val defaultDeclaration: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    suspend fun handle(command: RequestSignatureCommand): RequestSignatureResult =
        withContext(Dispatchers.IO) {
            val protocolEntity = visitProtocolRepository.findByVisitIdAndIdAndStudioId(
                command.visitId.value, command.protocolId.value, command.studioId.value
            ) ?: throw NotFoundException("Protokół nie został znaleziony")
            val protocol = protocolEntity.toDomain()

            if (protocol.status != VisitProtocolStatus.READY_FOR_SIGNATURE) {
                throw ValidationException("Dokument nie jest gotowy do podpisu (status: ${protocol.status})")
            }
            val documentS3Key = protocol.filledPdfS3Key
                ?: throw ValidationException("Dokument PDF nie został jeszcze wygenerowany")

            // Only one active signing session per document
            val active = signatureRequestRepository.findActiveForProtocol(
                command.studioId.value, command.protocolId.value, Instant.now()
            )
            if (active.isNotEmpty()) {
                throw ConflictException("Dla tego dokumentu istnieje już aktywne żądanie podpisu")
            }

            // WYSIWYS: hash the exact bytes that will be displayed on the tablet
            val documentBytes = s3StorageService.downloadBytes(documentS3Key)
            val documentSha256 = documentIntegrityService.sha256Hex(documentBytes)

            val visitEntity = visitRepository.findById(command.visitId.value).orElse(null)
                ?: throw EntityNotFoundException("Wizyta nie została znaleziona")

            val documentName = resolveDocumentName(protocol, command.studioId)

            val now = Instant.now()
            val request = SignatureRequest(
                id = SignatureRequestId.random(),
                studioId = command.studioId,
                visitId = command.visitId,
                protocolId = command.protocolId,
                tabletId = command.tabletId,
                status = SignatureRequestStatus.PENDING_DISPLAY,
                documentS3Key = documentS3Key,
                documentSha256 = documentSha256,
                documentName = documentName,
                signerName = command.signerName,
                declarationText = command.declarationText?.trim()?.ifBlank { null } ?: defaultDeclaration,
                requestedBy = command.userId,
                requestedByName = command.userName,
                createdAt = now,
                expiresAt = now.plusSeconds(requestTtlMinutes * 60),
                displayedAt = null,
                declarationAcceptedAt = null,
                signedAt = null,
                sealedAt = null,
                completedAt = null,
                signerIpAddress = null,
                signerDevice = null,
                signedPdfS3Key = null,
                sealApplied = false,
                timestampApplied = false,
                failureReason = null,
                updatedAt = now
            )
            signatureRequestRepository.save(SignatureRequestEntity.fromDomain(request))

            // Single-use anti-replay challenge, delivered to the tablet with the document
            val challenge = documentIntegrityService.issueChallenge(request.id.value)

            auditTrailService.append(
                requestId = request.id.value,
                studioId = command.studioId.value,
                eventType = SignatureAuditEventType.REQUEST_CREATED,
                actor = "${command.userName} [${command.userId.value}]",
                ipAddress = command.employeeIpAddress,
                details = "dokument=$documentName, sha256=$documentSha256, wizyta=${visitEntity.visitNumber}"
            )

            eventPublisher.publish(
                tenantId = command.studioId.value.toString(),
                requestId = request.id.toString(),
                eventType = "SIGNATURE_REQUESTED",
                tabletId = command.tabletId,
                documentName = documentName,
                signerName = command.signerName,
                status = request.status.name
            )

            logger.info(
                "Signature request {} created for protocol {} (sha256={})",
                request.id, command.protocolId, documentSha256
            )

            RequestSignatureResult(request = request, challenge = challenge)
        }

    private fun resolveDocumentName(
        protocol: pl.detailing.crm.protocol.domain.VisitProtocol,
        studioId: StudioId
    ): String {
        protocol.templateId?.let { templateId ->
            protocolTemplateRepository.findByIdAndStudioId(templateId.value, studioId.value)
                ?.toDomain()?.name?.let { return it }
        }
        protocol.consentDefinitionId?.let { definitionId ->
            consentDefinitionRepository.findByIdAndStudioId(definitionId.value, studioId.value)
                ?.name?.let { return it }
        }
        return "Dokument do podpisu"
    }
}

data class RequestSignatureCommand(
    val studioId: StudioId,
    val userId: UserId,
    val userName: String,
    val visitId: VisitId,
    val protocolId: VisitProtocolId,
    val tabletId: String?,
    val signerName: String,
    val declarationText: String?,
    val employeeIpAddress: String?
)

data class RequestSignatureResult(
    val request: SignatureRequest,
    val challenge: String
)
