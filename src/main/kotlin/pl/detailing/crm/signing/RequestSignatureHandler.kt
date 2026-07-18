package pl.detailing.crm.signing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.communication.CommunicationLogService
import pl.detailing.crm.communication.OutboundCommunicationGateway
import pl.detailing.crm.communication.RecordCommunicationCommand
import pl.detailing.crm.customer.consent.infrastructure.ConsentDefinitionRepository
import pl.detailing.crm.customer.infrastructure.CustomerRepository
import pl.detailing.crm.protocol.infrastructure.ProtocolTemplateRepository
import pl.detailing.crm.protocol.infrastructure.S3ProtocolStorageService
import pl.detailing.crm.protocol.infrastructure.VisitProtocolRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.signing.domain.SignatureAuditEventType
import pl.detailing.crm.signing.domain.SignatureChannel
import pl.detailing.crm.signing.domain.SignatureRequest
import pl.detailing.crm.signing.domain.SignatureRequestStatus
import pl.detailing.crm.signing.infrastructure.*
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.visitcard.VisitCardProperties
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64

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
    private val customerRepository: CustomerRepository,
    private val studioRepository: StudioRepository,
    private val studioSettingsRepository: StudioSettingsRepository,
    private val communicationGateway: OutboundCommunicationGateway,
    private val communicationLogService: CommunicationLogService,
    private val visitCardProperties: VisitCardProperties,
    @Value("\${signing.request.ttl-minutes:15}") private val requestTtlMinutes: Long,
    @Value("\${signing.request.sms-ttl-minutes:60}") private val smsRequestTtlMinutes: Long,
    @Value("\${signing.request.default-declaration:O\u015Bwiadczam, \u017Ce zapozna\u0142em/zapozna\u0142am si\u0119 z tre\u015Bci\u0105 niniejszego dokumentu, rozumiem jego tre\u015B\u0107 i akceptuj\u0119 zawarte w nim ustalenia.}")
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

            // SMS_LINK channel: the customer's phone number is the delivery address —
            // resolved server-side from the visit's customer record, never from the request
            val signerPhone: String? = if (command.channel == SignatureChannel.SMS_LINK) {
                val customer = customerRepository.findByIdAndStudioId(visitEntity.customerId, command.studioId.value)
                    ?: throw EntityNotFoundException("Klient nie został znaleziony")
                val phone = customer.phone?.takeIf { it.isNotBlank() }
                    ?: throw ValidationException("Nie podano numeru klienta")
                normalizePolishPhone(phone)
            } else null

            val documentName = resolveDocumentName(protocol, command.studioId)
            val ttlMinutes =
                if (command.channel == SignatureChannel.SMS_LINK) smsRequestTtlMinutes else requestTtlMinutes

            val now = Instant.now()
            val request = SignatureRequest(
                id = SignatureRequestId.random(),
                studioId = command.studioId,
                visitId = command.visitId,
                protocolId = command.protocolId,
                tabletId = if (command.channel == SignatureChannel.SMS_LINK) null else command.tabletId,
                channel = command.channel,
                signerPhone = signerPhone,
                linkToken = if (command.channel == SignatureChannel.SMS_LINK) generateLinkToken() else null,
                status = SignatureRequestStatus.PENDING_DISPLAY,
                documentS3Key = documentS3Key,
                documentSha256 = documentSha256,
                documentName = documentName,
                signerName = command.signerName,
                declarationText = command.declarationText?.trim()?.ifBlank { null } ?: defaultDeclaration,
                requestedBy = command.userId,
                requestedByName = command.userName,
                createdAt = now,
                expiresAt = now.plusSeconds(ttlMinutes * 60),
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

            // Single-use anti-replay challenge, delivered to the signing device with the document
            val challenge = documentIntegrityService.issueChallenge(
                request.id.value, Duration.ofMinutes(ttlMinutes)
            )

            auditTrailService.append(
                requestId = request.id.value,
                studioId = command.studioId.value,
                eventType = SignatureAuditEventType.REQUEST_CREATED,
                actor = "${command.userName} [${command.userId.value}]",
                ipAddress = command.employeeIpAddress,
                details = "dokument=$documentName, sha256=$documentSha256, wizyta=${visitEntity.visitNumber}, kanał=${command.channel}"
            )

            if (command.channel == SignatureChannel.SMS_LINK) {
                sendSigningLinkSms(request, visitEntity.customerId, documentName)
            }

            eventPublisher.publish(
                tenantId = command.studioId.value.toString(),
                requestId = request.id.toString(),
                eventType = "SIGNATURE_REQUESTED",
                tabletId = request.tabletId,
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

    /**
     * Deliver the tokenized signing link by SMS. Any delivery failure throws, rolling back
     * the whole request — a session the customer can never reach must not stay active
     * (it would block the "one active request per document" slot for 60 minutes).
     */
    private fun sendSigningLinkSms(request: SignatureRequest, customerId: java.util.UUID, documentName: String) {
        val phone = requireNotNull(request.signerPhone)
        val settings = studioSettingsRepository.findById(request.studioId.value).orElse(null)
        val studioName = settings?.name?.takeIf { it.isNotBlank() }
            ?: studioRepository.findByStudioId(request.studioId.value)?.name
            ?: "Studio detailingu"
        val signingUrl = "${visitCardProperties.frontendBaseUrl.trimEnd('/')}/sign/${request.linkToken}"
        val message = "$studioName: dokument „$documentName” czeka na Twój podpis. " +
            "Otwórz link, zapoznaj się z treścią i podpisz: $signingUrl"

        val result = try {
            communicationGateway.sendTransactionalSms(request.studioId.value, phone, message)
        } catch (e: InsufficientSmsCreditsException) {
            documentIntegrityService.invalidateChallenge(request.id.value)
            recordSmsLog(request, customerId, phone, message, success = false, error = "Brak kredytów SMS")
            throw e
        }
        recordSmsLog(request, customerId, phone, message, success = result.success, error = result.errorMessage)
        if (!result.success) {
            documentIntegrityService.invalidateChallenge(request.id.value)
            throw ValidationException("Nie udało się wysłać SMS z linkiem do podpisu: ${result.errorMessage ?: "błąd dostawcy"}")
        }
        logger.info("Signing link SMS sent for request {} (phone ends with …{})", request.id, phone.takeLast(3))
    }

    private fun recordSmsLog(
        request: SignatureRequest,
        customerId: java.util.UUID,
        phone: String,
        message: String,
        success: Boolean,
        error: String?
    ) {
        communicationLogService.record(
            RecordCommunicationCommand(
                studioId = request.studioId,
                customerId = CustomerId(customerId),
                visitId = request.visitId,
                channel = CommunicationChannel.SMS,
                messageType = CommunicationMessageType.SIGNATURE_LINK_SMS,
                recipientAddress = phone,
                subject = null,
                bodyContent = message,
                success = success,
                errorMessage = error
            )
        )
    }

    private fun generateLinkToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
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
    val channel: SignatureChannel = SignatureChannel.TABLET,
    val signerName: String,
    val declarationText: String?,
    val employeeIpAddress: String?
)

data class RequestSignatureResult(
    val request: SignatureRequest,
    val challenge: String
)
