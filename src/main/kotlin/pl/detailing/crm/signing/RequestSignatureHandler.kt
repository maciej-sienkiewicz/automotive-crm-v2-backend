package pl.detailing.crm.signing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import pl.detailing.crm.communication.CommunicationLogService
import pl.detailing.crm.communication.template.MessageTemplateRenderer
import pl.detailing.crm.smscampaigns.domain.SmsAutomationConfigRepository
import pl.detailing.crm.smscampaigns.domain.SmsNotificationRule
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
import pl.detailing.crm.subscription.entitlement.capability.CapabilityKey
import pl.detailing.crm.subscription.entitlement.capability.CapabilityService
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
    private val customerRepository: CustomerRepository,
    private val communicationGateway: OutboundCommunicationGateway,
    private val communicationLogService: CommunicationLogService,
    private val smsAutomationConfigRepository: SmsAutomationConfigRepository,
    private val renderer: MessageTemplateRenderer,
    private val visitCardProperties: VisitCardProperties,
    private val capabilityService: CapabilityService,
    private val transactionTemplate: TransactionTemplate,
    @Value("\${signing.request.ttl-minutes:15}") private val requestTtlMinutes: Long,
    @Value("\${signing.request.sms-ttl-minutes:60}") private val smsRequestTtlMinutes: Long,
    @Value("\${signing.request.default-declaration:O\u015Bwiadczam, \u017Ce zapozna\u0142em/zapozna\u0142am si\u0119 z tre\u015Bci\u0105 niniejszego dokumentu, rozumiem jego tre\u015B\u0107 i akceptuj\u0119 zawarte w nim ustalenia.}")
    private val defaultDeclaration: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    suspend fun handle(command: RequestSignatureCommand): RequestSignatureResult =
        withContext(Dispatchers.IO) {
            // Point-of-effect (W1) entitlement check — catches every caller, current and
            // future. TABLET needs the e-signatures module; SMS_LINK is the cross-module
            // rule: the signing link travels by SMS, so it additionally requires the
            // communication module (SIGNATURE_REMOTE_REQUEST = E_SIGNATURES ∧ SMS_EMAIL).
            val requiredCapability = when (command.channel) {
                SignatureChannel.TABLET -> CapabilityKey.SIGNATURE_LOCAL
                SignatureChannel.SMS_LINK -> CapabilityKey.SIGNATURE_REMOTE_REQUEST
            }
            capabilityService.requireCapability(command.studioId, requiredCapability)

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

            // Fail before doing any work when the SMS text this channel depends on is
            // missing. This used to surface only at delivery time, after the request row
            // had been written — and since that write was never rolled back, the studio
            // was left with an active session it could not use and could not replace.
            if (command.channel == SignatureChannel.SMS_LINK) {
                requireSigningSmsTemplate(command.studioId)
            }

            // WYSIWYS: hash the exact bytes that will be displayed on the tablet.
            // The bytes are already in memory here — they are cached below so document
            // delivery to the signing device does not repeat this S3 download.
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
                completedAt = null,
                signerIpAddress = null,
                signerDevice = null,
                signedPdfS3Key = null,
                failureReason = null,
                updatedAt = now
            )
            // The body of a `@Transactional suspend` function running on Dispatchers.IO
            // escapes the interceptor-managed transaction (see AuditLogWriter for the why),
            // so the template is used deliberately here — same shape as
            // CancelDraftVisitHandler. Without it every repository call committed on its
            // own and a failed SMS left the request row behind, blocking the single active
            // slot for the whole TTL.
            val challenge = transactionTemplate.execute {
                signatureRequestRepository.save(SignatureRequestEntity.fromDomain(request))

                // Single-use anti-replay challenge, delivered to the signing device with the document
                val issued = documentIntegrityService.issueChallenge(
                    request.id.value, Duration.ofMinutes(ttlMinutes)
                )

                // Serve-from-cache for the signing device (evicted with the challenge;
                // delivery re-verifies SHA-256, so WYSIWYS is unaffected)
                documentIntegrityService.cacheDocument(
                    request.id.value, documentBytes, Duration.ofMinutes(ttlMinutes)
                )

                auditTrailService.append(
                    requestId = request.id.value,
                    studioId = command.studioId.value,
                    eventType = SignatureAuditEventType.REQUEST_CREATED,
                    actor = "${command.userName} [${command.userId.value}]",
                    ipAddress = command.employeeIpAddress,
                    details = "dokument=$documentName, sha256=$documentSha256, wizyta=${visitEntity.visitNumber}, kanał=${command.channel}"
                )

                // Inside the transaction on purpose: a link the customer can never
                // receive must not leave an active session behind. The common failures —
                // no template, template disabled — are refused before this point.
                //
                // Uwaga na kolejność: SMS wychodzi z tego bloku NIEODWRACALNIE, a wszystko,
                // co dzieje się po nim, wciąż potrafi wywrócić transakcję i zabrać ze sobą
                // token linku. Dokładnie tak zepsuł się przepływ przy rozjeździe CHECK-a na
                // communication_log (V100__sync_enum_check_constraints.sql): klient dostawał
                // SMS-a, a link odpowiadał „nieprawidłowy lub wygasł". Wpis do dziennika
                // komunikacji ma dlatego własną transakcję (CommunicationLogService.record)
                // i nie jest w stanie unieważnić wysłanej już wiadomości.
                if (command.channel == SignatureChannel.SMS_LINK) {
                    sendSigningLinkSms(request, visitEntity.customerId, documentName)
                }

                issued
            } ?: error("Transakcja żądania podpisu nie zwróciła wyniku")

            // NOTE: the SIGNATURE_REQUESTED event is deliberately NOT published here.
            // This method runs inside a transaction; publishing from within it lets the
            // tablet's immediate GET /pending race the uncommitted INSERT (it sees 204
            // and the request waits for the next polling cycle). The controller publishes
            // the event after this transaction has committed.
            logger.info(
                "Signature request {} created for protocol {} (sha256={})",
                request.id, command.protocolId, documentSha256
            )

            RequestSignatureResult(request = request, challenge = challenge)
        }

    /**
     * The SMS text this channel depends on, or a refusal naming what to fix.
     *
     * Called once before anything is written, so a studio that never configured the
     * message is turned away for free, and once again at delivery time from the value
     * this returns — the config is a single row and the second read is the one that
     * matters for correctness.
     */
    private fun requireSigningSmsTemplate(studioId: StudioId): SmsNotificationRule {
        val rule = smsAutomationConfigRepository.findByStudioId(studioId)?.signatureRequest
            ?: throw ValidationException(
                "Nie skonfigurowano treści SMS-a z linkiem do podpisu — uzupełnij ją w Ustawieniach → Szablony SMS"
            )
        if (!rule.sendable) {
            throw ValidationException(
                "SMS z linkiem do podpisu jest wyłączony — włącz go w Ustawieniach → Szablony SMS"
            )
        }
        return rule
    }

    /**
     * Deliver the tokenized signing link by SMS. Any delivery failure throws, rolling back
     * the whole request — a session the customer can never reach must not stay active
     * (it would block the "one active request per document" slot for 60 minutes).
     *
     * The rollback is real only because the caller runs this inside a TransactionTemplate;
     * the enclosing `@Transactional suspend` annotation alone never opened one.
     */
    private fun sendSigningLinkSms(request: SignatureRequest, customerId: java.util.UUID, documentName: String) {
        val phone = requireNotNull(request.signerPhone)
        val signingUrl = "${visitCardProperties.frontendBaseUrl.trimEnd('/')}/sign/${request.linkToken}"

        val rule = requireSigningSmsTemplate(request.studioId)

        val customer = customerRepository.findByIdAndStudioId(customerId, request.studioId.value)
        val message = renderer.render(
            rule.messageTemplate,
            mapOf(
                "imie" to customer?.firstName.orEmpty(),
                "nazwisko" to customer?.lastName.orEmpty(),
                "dokument" to documentName,
                "link" to signingUrl
            )
        )

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
