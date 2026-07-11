package pl.detailing.crm.signing

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.*
import pl.detailing.crm.signing.domain.SignatureAuditEventType
import pl.detailing.crm.signing.domain.SignatureRequest
import pl.detailing.crm.signing.infrastructure.*
import java.time.Instant

/**
 * Non-happy-path transitions of a signing session: employee cancellation,
 * customer refusal on the tablet, TTL expiry. Every transition invalidates the
 * anti-replay challenge and is appended to the hash-chained audit trail.
 */
@Service
class SignatureRequestLifecycleService(
    private val signatureRequestRepository: SignatureRequestRepository,
    private val documentIntegrityService: DocumentIntegrityService,
    private val auditTrailService: SignatureAuditTrailService,
    private val eventPublisher: SignatureEventPublisher
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun cancel(studioId: StudioId, requestId: SignatureRequestId, cancelledBy: String, ipAddress: String?): SignatureRequest {
        val entity = signatureRequestRepository.findByIdAndStudioId(requestId.value, studioId.value)
            ?: throw NotFoundException("Żądanie podpisu nie zostało znalezione")
        val cancelled = entity.toDomain().cancel()
        signatureRequestRepository.save(SignatureRequestEntity.fromDomain(cancelled))
        documentIntegrityService.invalidateChallenge(requestId.value)

        auditTrailService.append(
            requestId = requestId.value,
            studioId = studioId.value,
            eventType = SignatureAuditEventType.REQUEST_CANCELLED,
            actor = cancelledBy,
            ipAddress = ipAddress
        )
        eventPublisher.publish(
            tenantId = studioId.value.toString(),
            requestId = requestId.toString(),
            eventType = "SIGNATURE_CANCELLED",
            tabletId = cancelled.tabletId,
            documentName = cancelled.documentName,
            signerName = cancelled.signerName,
            status = cancelled.status.name
        )
        return cancelled
    }

    @Transactional
    fun decline(
        studioId: StudioId,
        requestId: SignatureRequestId,
        tabletId: String,
        reason: String?,
        ipAddress: String?,
        userAgent: String?
    ): SignatureRequest {
        val entity = signatureRequestRepository.findByIdAndStudioId(requestId.value, studioId.value)
            ?: throw NotFoundException("Żądanie podpisu nie zostało znalezione")
        val request = entity.toDomain()
        if (request.tabletId != null && request.tabletId != tabletId) {
            throw ForbiddenException("Żądanie podpisu jest przypisane do innego tabletu")
        }
        val declined = request.decline(reason)
        signatureRequestRepository.save(SignatureRequestEntity.fromDomain(declined))
        documentIntegrityService.invalidateChallenge(requestId.value)

        auditTrailService.append(
            requestId = requestId.value,
            studioId = studioId.value,
            eventType = SignatureAuditEventType.REQUEST_DECLINED,
            actor = request.signerName,
            ipAddress = ipAddress,
            userAgent = userAgent,
            details = reason
        )
        eventPublisher.publish(
            tenantId = studioId.value.toString(),
            requestId = requestId.toString(),
            eventType = "SIGNATURE_DECLINED",
            tabletId = tabletId,
            documentName = declined.documentName,
            signerName = declined.signerName,
            status = declined.status.name,
            errorMessage = reason
        )
        return declined
    }

    /** Persist the EXPIRED status when an expired request is observed. */
    @Transactional
    fun markExpired(request: SignatureRequest): SignatureRequest {
        val expired = request.expire()
        signatureRequestRepository.save(SignatureRequestEntity.fromDomain(expired))
        documentIntegrityService.invalidateChallenge(request.id.value)
        logger.info("Signature request {} expired (created {})", request.id, request.createdAt)
        return expired
    }

    fun isEffectivelyExpired(request: SignatureRequest, now: Instant = Instant.now()): Boolean =
        request.isExpired(now)
}
