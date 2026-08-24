package pl.detailing.crm.push.call

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.push.infrastructure.PushDeviceRepository
import pl.detailing.crm.push.send.PushDeliveryStatus
import pl.detailing.crm.push.send.WebPushSender
import pl.detailing.crm.shared.UnprocessableEntityException
import pl.detailing.crm.shared.ValidationException
import java.time.Instant

/**
 * The desktop half of Click-to-Call: turns "user clicked a phone number"
 * into an encrypted Web Push to that same user's phone(s).
 *
 * The push goes ONLY to devices registered by the requesting user — never to
 * another user's phone and never across studios. The phone number travels
 * inside the encrypted payload (RFC 8291), so the push service (FCM/Mozilla/
 * Apple) relays an opaque blob and no customer PII in transit is readable by
 * a third party.
 *
 * The payload deliberately does NOT auto-dial anything. Browsers require a
 * user gesture to open tel:, and that is the correct UX anyway — the phone
 * shows a notification with a "Zadzwoń" action and the human taps it.
 */
@Service
class RequestCallHandler(
    private val pushDeviceRepository: PushDeviceRepository,
    private val webPushSender: WebPushSender,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(RequestCallHandler::class.java)

    @Transactional
    suspend fun handle(command: RequestCallCommand): RequestCallResult =
        withContext(Dispatchers.IO) {
            val phone = command.phoneNumber.filter { it.isDigit() || it == '+' }
            if (phone.count { it.isDigit() } < 7) {
                throw ValidationException("Nieprawidłowy numer telefonu")
            }

            val devices = pushDeviceRepository
                .findByStudioIdAndUserIdAndRevokedAtIsNull(command.studioId.value, command.userId.value)
                .filter { command.deviceId == null || it.id == command.deviceId }

            if (devices.isEmpty()) {
                // A valid request that cannot be carried out: no phone is paired.
                // 422 lets the frontend show "sparuj telefon" instead of a generic error.
                throw UnprocessableEntityException(
                    "Brak sparowanego telefonu. Włącz powiadomienia o połączeniach na swoim telefonie."
                )
            }

            val payload = objectMapper.writeValueAsString(
                ClickToCallPayload(
                    phoneNumber = phone,
                    displayName = command.displayName?.trim()?.ifBlank { null },
                    requestedBy = command.requestedByName,
                    requestedAt = Instant.now().toString()
                )
            )

            var delivered = 0
            var revoked = 0
            devices.forEach { device ->
                when (webPushSender.send(device.toDomain(), payload)) {
                    PushDeliveryStatus.DELIVERED -> {
                        delivered++
                        device.lastUsedAt = Instant.now()
                    }
                    PushDeliveryStatus.SUBSCRIPTION_GONE -> {
                        revoked++
                        device.revokedAt = Instant.now()
                    }
                    PushDeliveryStatus.FAILED -> Unit
                }
                pushDeviceRepository.save(device)
            }

            log.info(
                "[push] Click-to-call: userId={}, urządzeń={}, dostarczono={}, wygasło={}",
                command.userId.value, devices.size, delivered, revoked
            )
            RequestCallResult(
                requestedDevices = devices.size,
                deliveredDevices = delivered,
                revokedDevices = revoked
            )
        }

}

/**
 * Contract consumed by the Service Worker's `push` handler
 * (frontend: public/sw.js). Field names are part of the API.
 */
data class ClickToCallPayload(
    val type: String = "CLICK_TO_CALL",
    val phoneNumber: String,
    val displayName: String?,
    val requestedBy: String,
    val requestedAt: String
)
