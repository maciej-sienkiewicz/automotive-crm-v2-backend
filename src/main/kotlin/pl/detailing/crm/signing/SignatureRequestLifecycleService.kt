package pl.detailing.crm.signing

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.*
import pl.detailing.crm.signing.domain.SignatureAuditEventType
import pl.detailing.crm.signing.domain.SignatureRequest
import pl.detailing.crm.signing.infrastructure.*
import pl.detailing.crm.visit.infrastructure.VisitRepository
import java.time.Instant
import java.util.UUID

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
    private val eventPublisher: SignatureEventPublisher,
    private val visitRepository: VisitRepository
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

    /**
     * Anuluje żądania podpisu dokumentów wizyty, której już nie ma.
     *
     * Zgłoszenie z 29.09: pracownik porzucił przyjęcie (szkic wizyty usunięty razem
     * z protokołami) i przyjął auto od nowa z rezerwacji. Żądania podpisu pierwszej
     * wizyty zostały aktywne, a tablet podaje kolejkę od najstarszego - klient dostał
     * do podpisu protokół skasowanej wizyty i każda próba kończyła się „Wizyta nie
     * została znaleziona", aż żądania wygasły po 15 minutach. Anulowanie wysyła na
     * tablet SIGNATURE_CANCELLED, więc dokument znika z ekranu od razu.
     */
    @Transactional
    fun cancelActiveForVisit(studioId: StudioId, visitId: UUID, cancelledBy: String): Int {
        val active = signatureRequestRepository.findActiveForVisit(studioId.value, visitId)
        active.forEach { cancel(studioId, SignatureRequestId(it.id), cancelledBy, null) }
        if (active.isNotEmpty()) {
            logger.info("Cancelled {} signature request(s) of deleted visit {}", active.size, visitId)
        }
        return active.size
    }

    /**
     * Kolejka tabletu bez żądań, których wizyty już nie ma - te są przy okazji
     * anulowane. Siatka bezpieczeństwa dla [cancelActiveForVisit]: łapie żądania
     * osierocone inną drogą (albo sprzed tej poprawki), zanim zablokują tablet.
     */
    @Transactional
    fun withoutOrphaned(active: List<SignatureRequestEntity>): List<SignatureRequestEntity> {
        val visitIds = active.mapNotNull { it.visitId }.toSet()
        if (visitIds.isEmpty()) return active
        val existing = visitRepository.findAllById(visitIds).map { it.id }.toSet()
        val (orphaned, kept) = active.partition { it.visitId != null && it.visitId !in existing }
        orphaned.forEach { cancel(StudioId(it.studioId), SignatureRequestId(it.id), "System", null) }
        if (orphaned.isNotEmpty()) {
            logger.warn("Dropped {} orphaned signature request(s) from tablet queue: {}", orphaned.size, orphaned.map { it.id })
        }
        return kept
    }
}
