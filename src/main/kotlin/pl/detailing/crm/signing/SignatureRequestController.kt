package pl.detailing.crm.signing

import jakarta.servlet.http.HttpServletRequest
import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.shared.*
import pl.detailing.crm.signing.domain.SignatureRequest
import pl.detailing.crm.signing.infrastructure.SignatureEventPublisher
import pl.detailing.crm.signing.infrastructure.SignatureRequestRepository
import pl.detailing.crm.signing.infrastructure.TabletSessionService
import java.time.Instant

/**
 * CRM-side (session-authenticated) endpoints of the tablet signing flow.
 *
 * "Poproś o podpis" → POST /api/v1/visits/{visitId}/protocols/{protocolId}/signature-requests
 * The CRM then listens on /topic/studio.{studioId}.signature.{requestId} for live status.
 */
@RestController
@RequestMapping("/api/v1")
class SignatureRequestController(
    private val requestSignatureHandler: RequestSignatureHandler,
    private val lifecycleService: SignatureRequestLifecycleService,
    private val signatureRequestRepository: SignatureRequestRepository,
    private val tabletSessionService: TabletSessionService,
    private val eventPublisher: SignatureEventPublisher
) {

    /** Employee clicks "Poproś o podpis" — creates the signing session for the tablet. */
    @PostMapping("/visits/{visitId}/protocols/{protocolId}/signature-requests")
    fun requestSignature(
        @PathVariable visitId: String,
        @PathVariable protocolId: String,
        @RequestBody request: CreateSignatureRequestBody,
        httpRequest: HttpServletRequest
    ): ResponseEntity<SignatureRequestDto> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        if (request.signerName.isBlank()) {
            throw ValidationException("Imię i nazwisko osoby podpisującej jest wymagane")
        }

        val result = requestSignatureHandler.handle(
            RequestSignatureCommand(
                studioId = principal.studioId,
                userId = principal.userId,
                userName = principal.name,
                visitId = VisitId.fromString(visitId),
                protocolId = VisitProtocolId.fromString(protocolId),
                tabletId = request.tabletId?.trim()?.ifBlank { null },
                signerName = request.signerName.trim(),
                declarationText = request.declarationText,
                employeeIpAddress = clientIp(httpRequest)
            )
        )

        ResponseEntity.status(HttpStatus.CREATED).body(result.request.toDto())
    }

    /** Live status polling fallback (primary channel is the WebSocket topic). */
    @GetMapping("/signature-requests/{requestId}")
    fun getSignatureRequest(@PathVariable requestId: String): ResponseEntity<SignatureRequestDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        val entity = signatureRequestRepository.findByIdAndStudioId(
            SignatureRequestId.fromString(requestId).value, principal.studioId.value
        ) ?: throw NotFoundException("Żądanie podpisu nie zostało znalezione")

        var request = entity.toDomain()
        if (lifecycleService.isEffectivelyExpired(request)) {
            request = lifecycleService.markExpired(request)
        }
        return ResponseEntity.ok(request.toDto())
    }

    /** Employee cancels a pending request (e.g. customer stepped away). */
    @DeleteMapping("/signature-requests/{requestId}")
    fun cancelSignatureRequest(
        @PathVariable requestId: String,
        httpRequest: HttpServletRequest
    ): ResponseEntity<SignatureRequestDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        val cancelled = lifecycleService.cancel(
            studioId = principal.studioId,
            requestId = SignatureRequestId.fromString(requestId),
            cancelledBy = "${principal.name} [${principal.userId.value}]",
            ipAddress = clientIp(httpRequest)
        )
        return ResponseEntity.ok(cancelled.toDto())
    }

    // ==================== Tablet pairing (employee side) ====================

    /** Generate a one-time 6-digit code shown in the CRM and typed on the tablet. */
    @PostMapping("/tablets/pairing-codes")
    fun generatePairingCode(): ResponseEntity<PairingCodeResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val generated = tabletSessionService.generatePairingCode(
            tenantId = principal.studioId.value.toString(),
            userId = principal.userId.value.toString()
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(
            PairingCodeResponse(code = generated.code, expiresAt = generated.expiresAt)
        )
    }

    /** List all tablets currently paired to this studio. */
    @GetMapping("/tablets")
    fun listTablets(): ResponseEntity<List<TabletDto>> {
        val principal = SecurityContextHelper.getCurrentUser()
        val tablets = tabletSessionService.listTablets(principal.studioId.value.toString())
        return ResponseEntity.ok(tablets.map {
            TabletDto(
                tabletId = it.tabletId,
                deviceName = it.deviceName,
                pairedAt = it.pairedAt,
                tokenExpiresAt = it.tokenExpiresAt
            )
        })
    }

    /** Revoke a paired tablet (lost/replaced device). */
    @DeleteMapping("/tablets/{tabletId}")
    fun revokeTablet(@PathVariable tabletId: String): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        val tenantId = principal.studioId.value.toString()
        // Resolve deviceName before revoking so it can be included in the WS event
        val deviceName = tabletSessionService.listTablets(tenantId)
            .firstOrNull { it.tabletId == tabletId }?.deviceName ?: tabletId
        tabletSessionService.revokeTablet(tenantId, tabletId)
        eventPublisher.publishTabletEvent(
            tenantId = tenantId,
            tabletId = tabletId,
            deviceName = deviceName,
            eventType = "TABLET_REVOKED"
        )
        return ResponseEntity.noContent().build()
    }

    private fun clientIp(request: HttpServletRequest): String =
        request.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()
            ?: request.remoteAddr
}

// ==================== DTOs ====================

data class CreateSignatureRequestBody(
    /** Route to a specific paired tablet; null = any tablet in the studio. */
    val tabletId: String? = null,
    val signerName: String,
    /** Custom declaration; falls back to the configured default when null. */
    val declarationText: String? = null
)

data class PairingCodeResponse(
    val code: String,
    val expiresAt: Instant
)

data class TabletDto(
    val tabletId: String,
    val deviceName: String,
    val pairedAt: Instant,
    /** null when the TTL cannot be determined (e.g. key has no expiry set) */
    val tokenExpiresAt: Instant?
)

data class SignatureRequestDto(
    val id: String,
    val visitId: String,
    val protocolId: String,
    val tabletId: String?,
    val status: String,
    val documentName: String,
    val documentSha256: String,
    val signerName: String,
    val requestedByName: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val displayedAt: Instant?,
    val signedAt: Instant?,
    val completedAt: Instant?,
    val sealApplied: Boolean,
    val timestampApplied: Boolean,
    val failureReason: String?
)

internal fun SignatureRequest.toDto() = SignatureRequestDto(
    id = id.toString(),
    visitId = visitId.toString(),
    protocolId = protocolId.toString(),
    tabletId = tabletId,
    status = status.name,
    documentName = documentName,
    documentSha256 = documentSha256,
    signerName = signerName,
    requestedByName = requestedByName,
    createdAt = createdAt,
    expiresAt = expiresAt,
    displayedAt = displayedAt,
    signedAt = signedAt,
    completedAt = completedAt,
    sealApplied = sealApplied,
    timestampApplied = timestampApplied,
    failureReason = failureReason
)
