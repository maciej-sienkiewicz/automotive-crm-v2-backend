package pl.detailing.crm.signing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.audit.domain.AuditAction
import pl.detailing.crm.audit.domain.AuditModule
import pl.detailing.crm.audit.domain.AuditService
import pl.detailing.crm.audit.domain.LogAuditCommand
import pl.detailing.crm.customer.consent.domain.CustomerConsent
import pl.detailing.crm.customer.consent.infrastructure.ConsentTemplateRepository
import pl.detailing.crm.customer.consent.infrastructure.CustomerConsentEntity
import pl.detailing.crm.customer.consent.infrastructure.CustomerConsentRepository
import pl.detailing.crm.protocol.infrastructure.S3ProtocolStorageService
import pl.detailing.crm.protocol.infrastructure.VisitProtocolEntity
import pl.detailing.crm.protocol.infrastructure.VisitProtocolRepository
import pl.detailing.crm.shared.*
import pl.detailing.crm.signing.domain.SignatureAuditEventType
import pl.detailing.crm.signing.domain.SignatureRequestStatus
import pl.detailing.crm.signing.infrastructure.*
import pl.detailing.crm.visit.infrastructure.DocumentService
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant
import java.util.Base64

/**
 * Accepts the signature packet from the tablet and closes the cryptographic loop:
 *
 *  1. Anti-replay: the single-use challenge is consumed ATOMICALLY — a captured
 *     submission can never be replayed.
 *  2. WYSIWYS: the hash echoed by the tablet must equal the hash computed when the
 *     employee requested the signature, AND the bytes re-downloaded from storage are
 *     re-hashed to prove nothing changed in between. Mismatch → session FAILED.
 *  3. RAM-only signature processing: the bitmap is normalized (transparent strokes
 *     only), merged into the PDF and wiped. It is never persisted anywhere.
 *  4. The audit page (Karta Podpisu) is appended, then the qualified seal +
 *     timestamp are applied over the WHOLE document.
 *  5. Only the sealed PDF is uploaded; the protocol becomes SIGNED (immutable).
 */
@Service
class SubmitSignatureHandler(
    private val signatureRequestRepository: SignatureRequestRepository,
    private val visitProtocolRepository: VisitProtocolRepository,
    private val visitRepository: VisitRepository,
    private val documentService: DocumentService,
    private val s3StorageService: S3ProtocolStorageService,
    private val documentIntegrityService: DocumentIntegrityService,
    private val signatureImageProcessor: SignatureImageProcessor,
    private val signedDocumentComposer: SignedDocumentComposer,
    private val qualifiedSealService: QualifiedSealService,
    private val auditTrailService: SignatureAuditTrailService,
    private val eventPublisher: SignatureEventPublisher,
    private val consentTemplateRepository: ConsentTemplateRepository,
    private val customerConsentRepository: CustomerConsentRepository,
    private val auditService: AuditService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    suspend fun handle(command: SubmitSignatureCommand): SubmitSignatureResult =
        withContext(Dispatchers.IO) {
            val entity = signatureRequestRepository.findByIdAndStudioId(
                command.requestId.value, command.studioId.value
            ) ?: throw NotFoundException("Żądanie podpisu nie zostało znalezione")
            var request = entity.toDomain()

            // Tablet routing check: a request pinned to a tablet may only be signed there
            if (request.tabletId != null && request.tabletId != command.tabletId) {
                throw ForbiddenException("Żądanie podpisu jest przypisane do innego tabletu")
            }
            if (request.isTerminal()) {
                throw ConflictException("Żądanie podpisu zostało już zakończone (status: ${request.status})")
            }
            if (request.isExpired()) {
                persist(request.expire())
                documentIntegrityService.invalidateChallenge(request.id.value)
                throw ValidationException("Żądanie podpisu wygasło — poproś pracownika o ponowne wysłanie dokumentu")
            }
            if (request.status != SignatureRequestStatus.DISPLAYED) {
                throw ValidationException("Dokument nie został jeszcze wyświetlony na tablecie")
            }
            if (!command.declarationAccepted) {
                throw ValidationException("Podpis wymaga wcześniejszej akceptacji oświadczenia o zapoznaniu się z treścią dokumentu")
            }

            val tabletActor = "TABLET ${command.tabletId} (${command.deviceName})"

            // ── 1. Anti-replay: consume the single-use challenge atomically ─────────
            if (!documentIntegrityService.consumeChallenge(request.id.value, command.challenge)) {
                request = fail(request, "Nieprawidłowy lub zużyty token sesji podpisu (możliwa próba powtórzenia)", tabletActor, command)
                throw ForbiddenException("Sesja podpisu jest nieważna — możliwa próba ponownego użycia podpisu została zablokowana")
            }

            auditTrailService.append(
                requestId = request.id.value,
                studioId = request.studioId.value,
                eventType = SignatureAuditEventType.DECLARATION_ACCEPTED,
                actor = request.signerName,
                ipAddress = command.ipAddress,
                userAgent = command.userAgent,
                details = "Zaakceptowano oświadczenie: „${request.declarationText}”",
                occurredAt = command.declarationAcceptedAt ?: Instant.now()
            )
            auditTrailService.append(
                requestId = request.id.value,
                studioId = request.studioId.value,
                eventType = SignatureAuditEventType.SIGNATURE_SUBMITTED,
                actor = request.signerName,
                ipAddress = command.ipAddress,
                userAgent = command.userAgent,
                details = "Skrót dokumentu przesłany z tabletu: ${command.documentSha256}"
            )

            // ── 2. WYSIWYS: tablet hash must equal the requested-document hash ──────
            if (!documentIntegrityService.digestsMatch(request.documentSha256, command.documentSha256)) {
                request = fail(request, "Niezgodność skrótu SHA-256 dokumentu (tablet: ${command.documentSha256}, oczekiwany: ${request.documentSha256})", tabletActor, command)
                throw ValidationException("Podpisany dokument nie jest tożsamy z dokumentem oczekującym na podpis — proces przerwany")
            }

            // Defense in depth: re-download and re-hash — storage must be unchanged too
            val filledPdfBytes = s3StorageService.downloadBytes(request.documentS3Key)
            val storageSha256 = documentIntegrityService.sha256Hex(filledPdfBytes)
            if (!documentIntegrityService.digestsMatch(request.documentSha256, storageSha256)) {
                request = fail(request, "Dokument w magazynie został zmieniony po utworzeniu żądania podpisu (sha256=$storageSha256)", tabletActor, command)
                throw ValidationException("Integralność dokumentu została naruszona — proces przerwany")
            }

            auditTrailService.append(
                requestId = request.id.value,
                studioId = request.studioId.value,
                eventType = SignatureAuditEventType.HASH_VERIFIED,
                actor = "SYSTEM",
                details = "sha256=${request.documentSha256} — zgodność potwierdzona (tablet + magazyn dokumentów)"
            )

            // ── 3. RAM-only signature processing ────────────────────────────────────
            val rawSignatureBytes: ByteArray = try {
                Base64.getDecoder().decode(command.signatureImageBase64)
            } catch (e: IllegalArgumentException) {
                throw ValidationException("Obraz podpisu nie jest poprawnym base64")
            }

            var normalizedSignature: ByteArray? = null
            val signedAt = Instant.now()
            try {
                normalizedSignature = signatureImageProcessor.normalizeToTransparentPng(rawSignatureBytes)

                request = request.markDisplayed() // idempotent safety before completion transition

                val visitEntity = visitRepository.findById(request.visitId.value).orElse(null)
                    ?: throw EntityNotFoundException("Wizyta nie została znaleziona")

                // ── 4. Compose: PDF + signature strokes + audit page, then seal ─────
                val auditEvents = auditTrailService.eventsFor(request.id.value)
                logger.info(
                    "Composing signed document: requestId={} protocolId={} " +
                        "filledPdfS3Key={} filledPdfBytes={}B auditEvents={}",
                    request.id, request.protocolId,
                    request.documentS3Key, filledPdfBytes.size, auditEvents.size
                )
                val composedPdf = signedDocumentComposer.compose(
                    filledPdfBytes = filledPdfBytes,
                    signaturePngBytes = normalizedSignature,
                    request = request.copy(
                        signerIpAddress = command.ipAddress,
                        signerDevice = command.deviceName,
                        signedAt = signedAt
                    ),
                    auditEvents = auditEvents,
                    visitNumber = visitEntity.visitNumber,
                    sealInfo = qualifiedSealService.describe()
                )
                logger.info(
                    "Composed PDF ready: requestId={} composedBytes={}B — applying qualified seal",
                    request.id, composedPdf.size
                )

                val sealResult = qualifiedSealService.seal(composedPdf)
                logger.info(
                    "Seal result: requestId={} sealedBytes={}B sealApplied={} timestampApplied={}",
                    request.id, sealResult.pdfBytes.size, sealResult.sealApplied, sealResult.timestampApplied
                )
                val sealedAt = if (sealResult.sealApplied) Instant.now() else null
                if (sealResult.sealApplied) {
                    auditTrailService.append(
                        requestId = request.id.value,
                        studioId = request.studioId.value,
                        eventType = SignatureAuditEventType.DOCUMENT_SEALED,
                        actor = "SYSTEM",
                        details = qualifiedSealService.describe() +
                            (if (sealResult.timestampApplied) " Znacznik czasu: nałożony." else "")
                    )
                }

                // ── 5. Persist ONLY the sealed PDF; mark the protocol SIGNED ────────
                val protocolEntity = visitProtocolRepository.findByVisitIdAndIdAndStudioId(
                    request.visitId.value, request.protocolId.value, request.studioId.value
                ) ?: throw NotFoundException("Protokół nie został znaleziony")
                val protocol = protocolEntity.toDomain()
                if (protocol.status != VisitProtocolStatus.READY_FOR_SIGNATURE) {
                    throw ConflictException("Protokół nie oczekuje już na podpis (status: ${protocol.status})")
                }

                val signedPdfS3Key = s3StorageService.buildSignedPdfS3Key(
                    request.studioId.value, request.visitId.value, visitEntity.visitNumber, protocol.version
                )
                logger.info(
                    "Uploading signed PDF to S3: requestId={} key={} size={}B",
                    request.id, signedPdfS3Key, sealResult.pdfBytes.size
                )
                s3StorageService.uploadBytes(signedPdfS3Key, sealResult.pdfBytes)
                logger.info("S3 upload complete: requestId={} key={}", request.id, signedPdfS3Key)

                // Update the document record so the /documents endpoint serves the signed PDF
                documentService.replaceS3Key(request.documentS3Key, signedPdfS3Key)

                val signedProtocol = protocol.sign(
                    signedPdfS3Key = signedPdfS3Key,
                    signedBy = request.signerName,
                    signatureImageS3Key = null,   // RAM-only processing — no image is stored
                    notes = null
                )
                visitProtocolRepository.save(VisitProtocolEntity.fromDomain(signedProtocol))

                // Consent protocols additionally create the immutable CustomerConsent record
                protocol.consentDefinitionId?.let { definitionId ->
                    recordCustomerConsent(
                        definitionId, request.studioId,
                        CustomerId(visitEntity.customerId), request.requestedBy
                    )
                }

                request = request.complete(
                    signedPdfS3Key = signedPdfS3Key,
                    declarationAcceptedAt = command.declarationAcceptedAt ?: signedAt,
                    signedAt = signedAt,
                    sealedAt = sealedAt,
                    signerIpAddress = command.ipAddress,
                    signerDevice = command.deviceName,
                    sealApplied = sealResult.sealApplied,
                    timestampApplied = sealResult.timestampApplied
                )
                persist(request)

                auditTrailService.append(
                    requestId = request.id.value,
                    studioId = request.studioId.value,
                    eventType = SignatureAuditEventType.REQUEST_COMPLETED,
                    actor = "SYSTEM",
                    details = "signedPdfS3Key=$signedPdfS3Key, pieczęć=${sealResult.sealApplied}, znacznikCzasu=${sealResult.timestampApplied}"
                )

                auditService.log(LogAuditCommand(
                    studioId = request.studioId,
                    userId = request.requestedBy,
                    userDisplayName = request.requestedByName,
                    module = AuditModule.VISIT,
                    entityId = request.visitId.value.toString(),
                    entityDisplayName = visitEntity.visitNumber,
                    action = AuditAction.PROTOCOL_SIGNED,
                    metadata = mapOf(
                        "protocolId" to request.protocolId.toString(),
                        "signatureRequestId" to request.id.toString(),
                        "documentSha256" to request.documentSha256,
                        "signerName" to request.signerName,
                        "signerIp" to (command.ipAddress ?: ""),
                        "sealApplied" to sealResult.sealApplied.toString(),
                        "timestampApplied" to sealResult.timestampApplied.toString()
                    )
                ))

                eventPublisher.publish(
                    tenantId = request.studioId.value.toString(),
                    requestId = request.id.toString(),
                    eventType = "SIGNATURE_COMPLETED",
                    tabletId = command.tabletId,
                    documentName = request.documentName,
                    signerName = request.signerName,
                    status = request.status.name
                )

                logger.info(
                    "Signature request {} completed (protocol={}, seal={}, tsa={})",
                    request.id, request.protocolId, sealResult.sealApplied, sealResult.timestampApplied
                )

                SubmitSignatureResult(
                    requestId = request.id,
                    status = request.status,
                    sealApplied = sealResult.sealApplied,
                    timestampApplied = sealResult.timestampApplied
                )
            } finally {
                // Immediate destruction of the graphic signature from server memory:
                // both the raw upload and the normalized bitmap are zeroed here,
                // regardless of whether the transaction succeeded.
                signatureImageProcessor.wipe(rawSignatureBytes)
                signatureImageProcessor.wipe(normalizedSignature)
            }
        }

    private fun fail(
        request: pl.detailing.crm.signing.domain.SignatureRequest,
        reason: String,
        actor: String,
        command: SubmitSignatureCommand
    ): pl.detailing.crm.signing.domain.SignatureRequest {
        val failed = request.fail(reason)
        persist(failed)
        documentIntegrityService.invalidateChallenge(request.id.value)
        auditTrailService.append(
            requestId = request.id.value,
            studioId = request.studioId.value,
            eventType = SignatureAuditEventType.REQUEST_FAILED,
            actor = actor,
            ipAddress = command.ipAddress,
            userAgent = command.userAgent,
            details = reason
        )
        eventPublisher.publish(
            tenantId = request.studioId.value.toString(),
            requestId = request.id.toString(),
            eventType = "SIGNATURE_FAILED",
            tabletId = command.tabletId,
            documentName = request.documentName,
            signerName = request.signerName,
            status = failed.status.name,
            errorMessage = reason
        )
        logger.warn("Signature request {} FAILED: {}", request.id, reason)
        return failed
    }

    private fun persist(request: pl.detailing.crm.signing.domain.SignatureRequest) {
        signatureRequestRepository.save(SignatureRequestEntity.fromDomain(request))
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

data class SubmitSignatureCommand(
    val studioId: StudioId,
    val requestId: SignatureRequestId,
    val tabletId: String,
    val deviceName: String,
    /** SHA-256 (hex) the tablet computed over the PDF bytes it displayed. */
    val documentSha256: String,
    /** Single-use anti-replay nonce delivered together with the document. */
    val challenge: String,
    val declarationAccepted: Boolean,
    val declarationAcceptedAt: Instant?,
    /** PNG with alpha channel, base64-encoded — strokes only, no background. */
    val signatureImageBase64: String,
    val ipAddress: String?,
    val userAgent: String?
)

data class SubmitSignatureResult(
    val requestId: SignatureRequestId,
    val status: SignatureRequestStatus,
    val sealApplied: Boolean,
    val timestampApplied: Boolean
)
