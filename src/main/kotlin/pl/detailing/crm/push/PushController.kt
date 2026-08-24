package pl.detailing.crm.push

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.push.call.RequestCallCommand
import pl.detailing.crm.push.call.RequestCallHandler
import pl.detailing.crm.push.infrastructure.PushDeviceRepository
import pl.detailing.crm.push.register.RegisterPushDeviceCommand
import pl.detailing.crm.push.register.RegisterPushDeviceHandler
import pl.detailing.crm.push.send.WebPushSender
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.UnprocessableEntityException
import java.time.Instant
import java.util.UUID

/**
 * Click-to-Call over Web Push.
 *
 * Every endpoint is self-service and session-scoped: a user manages ONLY their
 * own paired phones and can ring ONLY their own phones. No dedicated
 * permission — the ability to see a customer's phone number (PII masking,
 * module gates) is enforced where the number is displayed, not here.
 *
 * Desktop vs phone is not a property of the session (both hold the same
 * SESSION cookie and the same UserPrincipal): the phone distinguishes itself
 * by the very act of registering a PushSubscription — only the device that is
 * supposed to RING ever calls POST /devices, and the device that ORDERS the
 * call is simply whichever session calls POST /call-requests.
 */
@RestController
@RequestMapping("/api/v1/push")
class PushController(
    private val registerPushDeviceHandler: RegisterPushDeviceHandler,
    private val requestCallHandler: RequestCallHandler,
    private val pushDeviceRepository: PushDeviceRepository,
    private val webPushSender: WebPushSender
) {

    /**
     * GET /api/v1/push/vapid-public-key
     * The applicationServerKey the phone passes to pushManager.subscribe().
     */
    @GetMapping("/vapid-public-key")
    fun getVapidPublicKey(): ResponseEntity<VapidPublicKeyResponse> {
        if (!webPushSender.isConfigured) {
            throw UnprocessableEntityException("Powiadomienia push nie są skonfigurowane na serwerze")
        }
        return ResponseEntity.ok(VapidPublicKeyResponse(publicKey = webPushSender.publicKey))
    }

    /**
     * POST /api/v1/push/devices
     * Called from the PHONE after pushManager.subscribe() succeeds.
     * Idempotent upsert keyed by the subscription endpoint.
     */
    @PostMapping("/devices")
    fun registerDevice(@RequestBody request: RegisterPushDeviceRequest): ResponseEntity<PushDeviceDto> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val device = registerPushDeviceHandler.handle(
            RegisterPushDeviceCommand(
                studioId = principal.studioId,
                userId = principal.userId,
                endpoint = request.endpoint,
                p256dh = request.p256dh,
                auth = request.auth,
                deviceName = request.deviceName,
                userAgent = request.userAgent
            )
        )

        ResponseEntity.status(HttpStatus.CREATED).body(device.toDto())
    }

    /**
     * GET /api/v1/push/devices
     * The caller's paired phones, newest first (revoked included, flagged).
     */
    @GetMapping("/devices")
    fun listDevices(): ResponseEntity<List<PushDeviceDto>> {
        val principal = SecurityContextHelper.getCurrentUser()

        val devices = pushDeviceRepository
            .findByStudioIdAndUserIdOrderByCreatedAtDesc(principal.studioId.value, principal.userId.value)
            .map { it.toDomain().toDto() }

        return ResponseEntity.ok(devices)
    }

    /**
     * DELETE /api/v1/push/devices/{id}
     * Soft-revoke; the phone additionally unsubscribes locally on its own.
     */
    @DeleteMapping("/devices/{id}")
    fun revokeDevice(@PathVariable id: String): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()

        val deviceId = runCatching { UUID.fromString(id) }.getOrNull()
            ?: throw NotFoundException("Nie znaleziono urządzenia")
        val device = pushDeviceRepository
            .findByIdAndStudioIdAndUserId(deviceId, principal.studioId.value, principal.userId.value)
            ?: throw NotFoundException("Nie znaleziono urządzenia")

        device.revokedAt = Instant.now()
        pushDeviceRepository.save(device)

        return ResponseEntity.noContent().build()
    }

    /**
     * POST /api/v1/push/call-requests
     * Called from the DESKTOP when the user clicks a phone number.
     */
    @PostMapping("/call-requests")
    fun requestCall(@RequestBody request: RequestCallRequest): ResponseEntity<RequestCallResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()

        val result = requestCallHandler.handle(
            RequestCallCommand(
                studioId = principal.studioId,
                userId = principal.userId,
                requestedByName = principal.fullName,
                phoneNumber = request.phoneNumber,
                displayName = request.displayName,
                deviceId = request.deviceId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            )
        )

        ResponseEntity.status(HttpStatus.ACCEPTED).body(
            RequestCallResponse(
                requestedDevices = result.requestedDevices,
                deliveredDevices = result.deliveredDevices
            )
        )
    }
}
