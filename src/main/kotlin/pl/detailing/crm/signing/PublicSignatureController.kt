package pl.detailing.crm.signing

import pl.detailing.crm.shared.pii.Pii
import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.protocol.infrastructure.S3ProtocolStorageService
import pl.detailing.crm.shared.*
import pl.detailing.crm.signing.domain.SignatureAuditEventType
import pl.detailing.crm.signing.domain.SignatureChannel
import pl.detailing.crm.signing.domain.SignatureRequest
import pl.detailing.crm.signing.infrastructure.*
import java.time.Instant

/**
 * Session-free controller for signing a document on the CUSTOMER'S OWN PHONE,
 * opened from a tokenized link delivered by SMS ("Wyślij prośbę na telefon klienta").
 *
 * The unguessable link token (256 bits, single request, TTL-bound) is the sole
 * credential — the same trust model as the public Visit Card (/api/public/visit-card).
 * The signing contract is identical to the tablet flow (WYSIWYS loop):
 *
 *  1. GET  /api/public/signing/{token}           → session metadata + single-use challenge
 *  2. GET  /api/public/signing/{token}/document  → EXACT PDF bytes (hash re-verified at delivery)
 *  3. POST /api/public/signing/{token}/submit    → signature PNG + client-side hash + challenge
 *     (or POST /decline when the customer refuses)
 *
 * Requests reachable here are ONLY those created with channel=SMS_LINK; tablet
 * sessions have no link token and can never be resolved through this controller.
 */
@RestController
@RequestMapping("/api/public/signing", produces = ["application/json;charset=UTF-8"])
class PublicSignatureController(
    private val signatureRequestRepository: SignatureRequestRepository,
    private val submitSignatureHandler: SubmitSignatureHandler,
    private val lifecycleService: SignatureRequestLifecycleService,
    private val s3StorageService: S3ProtocolStorageService,
    private val documentIntegrityService: DocumentIntegrityService,
    private val auditTrailService: SignatureAuditTrailService,
    private val eventPublisher: SignatureEventPublisher
) {

    companion object {
        /** Stands in for the tablet id in the shared submit/decline pipeline. */
        const val REMOTE_DEVICE_ID = "SMS_LINK"
        const val REMOTE_DEVICE_NAME = "Telefon klienta (link SMS)"
    }

    /** Session metadata for the public signing page. Terminal/expired sessions still resolve so the page can explain what happened. */
    @GetMapping("/{token}")
    fun getSession(@PathVariable token: String): ResponseEntity<PublicSigningSessionDto> {
        var request = resolveRequest(token)

        if (lifecycleService.isEffectivelyExpired(request)) {
            request = lifecycleService.markExpired(request)
        }

        val challenge = if (!request.isTerminal()) {
            documentIntegrityService.peekChallenge(request.id.value)
        } else null

        return ResponseEntity.ok(
            PublicSigningSessionDto(
                status = request.status.name,
                documentName = request.documentName,
                signerName = request.signerName,
                declarationText = request.declarationText,
                documentSha256 = request.documentSha256,
                challenge = challenge,
                expiresAt = request.expiresAt,
                failureReason = request.failureReason
            )
        )
    }

    /**
     * Stream the EXACT PDF bytes awaiting signature. Mirrors the tablet endpoint:
     * bytes are re-hashed as they leave the server; a changed stored object refuses delivery.
     */
    @GetMapping("/{token}/document")
    @Transactional
    fun getDocument(
        @PathVariable token: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<ByteArray> {
        val request = resolveRequest(token)

        if (request.isExpired()) {
            lifecycleService.markExpired(request)
            throw ValidationException("Żądanie podpisu wygasło")
        }
        if (request.isTerminal()) {
            throw ConflictException("Żądanie podpisu zostało już zakończone")
        }

        val pdfBytes = s3StorageService.downloadBytes(request.documentS3Key)
        val actualSha256 = documentIntegrityService.sha256Hex(pdfBytes)
        if (!documentIntegrityService.digestsMatch(request.documentSha256, actualSha256)) {
            throw ConflictException("Integralność dokumentu została naruszona — dokument nie zostanie wyświetlony")
        }

        val displayed = request.markDisplayed()
        signatureRequestRepository.save(SignatureRequestEntity.fromDomain(displayed))

        auditTrailService.append(
            requestId = request.id.value,
            studioId = request.studioId.value,
            eventType = SignatureAuditEventType.DOCUMENT_DELIVERED,
            actor = REMOTE_DEVICE_NAME,
            ipAddress = clientIp(httpRequest),
            userAgent = httpRequest.getHeader(HttpHeaders.USER_AGENT),
            details = "sha256=$actualSha256 — zgodność z żądaniem potwierdzona przy dostarczeniu (link SMS)"
        )
        eventPublisher.publish(
            tenantId = request.studioId.value.toString(),
            requestId = request.id.toString(),
            eventType = "SIGNATURE_DISPLAYED",
            tabletId = null,
            documentName = request.documentName,
            signerName = request.signerName,
            status = displayed.status.name
        )

        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header("X-Document-Sha256", actualSha256)
            .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"document.pdf\"")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(pdfBytes)
    }

    /** Submit the signature packet — same verification pipeline as the tablet. */
    @PostMapping("/{token}/submit")
    fun submitSignature(
        @PathVariable token: String,
        @RequestBody body: PublicSubmitSignatureRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<PublicSubmitSignatureResponse> = runBlocking {
        val request = resolveRequest(token)

        if (body.signatureImageBase64.isBlank()) {
            throw ValidationException("Obraz podpisu jest wymagany")
        }
        if (body.documentSha256.isBlank() || body.challenge.isBlank()) {
            throw ValidationException("Brak danych integralności dokumentu (hash/challenge)")
        }

        val result = submitSignatureHandler.handle(
            SubmitSignatureCommand(
                studioId = request.studioId,
                requestId = request.id,
                tabletId = REMOTE_DEVICE_ID,
                deviceName = REMOTE_DEVICE_NAME,
                documentSha256 = body.documentSha256.trim(),
                challenge = body.challenge,
                declarationAccepted = body.declarationAccepted,
                declarationAcceptedAt = body.declarationAcceptedAt,
                signatureImageBase64 = body.signatureImageBase64,
                ipAddress = clientIp(httpRequest),
                userAgent = httpRequest.getHeader(HttpHeaders.USER_AGENT)
            )
        )

        ResponseEntity.ok(
            PublicSubmitSignatureResponse(status = result.status.name)
        )
    }

    /** Customer refuses to sign on their phone. */
    @PostMapping("/{token}/decline")
    fun declineSignature(
        @PathVariable token: String,
        @RequestBody(required = false) body: PublicDeclineRequest?,
        httpRequest: HttpServletRequest
    ): ResponseEntity<PublicSubmitSignatureResponse> {
        val request = resolveRequest(token)
        val declined = lifecycleService.decline(
            studioId = request.studioId,
            requestId = request.id,
            tabletId = REMOTE_DEVICE_ID,
            reason = body?.reason,
            ipAddress = clientIp(httpRequest),
            userAgent = httpRequest.getHeader(HttpHeaders.USER_AGENT)
        )
        return ResponseEntity.ok(PublicSubmitSignatureResponse(status = declined.status.name))
    }

    private fun resolveRequest(token: String): SignatureRequest {
        if (token.isBlank() || token.length > 100) {
            throw NotFoundException("Żądanie podpisu nie zostało znalezione")
        }
        val entity = signatureRequestRepository.findByLinkToken(token)
            ?: throw NotFoundException("Żądanie podpisu nie zostało znalezione")
        val request = entity.toDomain()
        if (request.channel != SignatureChannel.SMS_LINK) {
            throw NotFoundException("Żądanie podpisu nie zostało znalezione")
        }
        return request
    }

    private fun clientIp(request: HttpServletRequest): String =
        request.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()
            ?: request.remoteAddr
}

// ==================== DTOs ====================

data class PublicSigningSessionDto(
    val status: String,
    val documentName: String,
    // The signer confirms their own data on their own phone — same PII rationale as the tablet
    @Pii val signerName: String,
    val declarationText: String,
    /** Expected SHA-256 — the phone must verify the downloaded bytes against it. */
    val documentSha256: String,
    /** Single-use anti-replay nonce; null once the session is terminal. */
    val challenge: String?,
    val expiresAt: Instant,
    val failureReason: String?
)

data class PublicSubmitSignatureRequest(
    /** SHA-256 (hex) computed by the phone over the PDF bytes it displayed. */
    val documentSha256: String,
    val challenge: String,
    val declarationAccepted: Boolean,
    val declarationAcceptedAt: Instant? = null,
    /** PNG with alpha channel (transparent background), base64. */
    val signatureImageBase64: String
)

data class PublicDeclineRequest(
    val reason: String? = null
)

data class PublicSubmitSignatureResponse(
    val status: String
)
