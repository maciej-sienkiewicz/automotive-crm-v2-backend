package pl.detailing.crm.push.send

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import pl.detailing.crm.push.domain.PushDevice
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/** Outcome of one delivery attempt to one device. */
enum class PushDeliveryStatus {
    /** Accepted by the push service (2xx). */
    DELIVERED,

    /** 404/410 — the subscription no longer exists; the row must be revoked. */
    SUBSCRIPTION_GONE,

    /** Any other failure (5xx, network, misconfiguration). */
    FAILED
}

/**
 * Delivers encrypted Web Push messages with VAPID authorization.
 *
 * A click-to-call push is worthless a minute after the click — the user is
 * standing at the desk with the customer's card open NOW. Hence TTL of 60 s
 * (the push service drops the message instead of ringing the phone an hour
 * later) and Urgency: high (the device radio is woken immediately, which
 * matters on Android in Doze mode).
 */
@Service
class WebPushSender(
    @Value("\${webpush.vapid.public-key:}") private val vapidPublicKey: String,
    @Value("\${webpush.vapid.private-key:}") private val vapidPrivateKey: String,
    @Value("\${webpush.vapid.subject:}") private val vapidSubject: String
) {

    private val log = LoggerFactory.getLogger(WebPushSender::class.java)

    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    val isConfigured: Boolean
        get() = vapidPublicKey.isNotBlank() && vapidPrivateKey.isNotBlank() && vapidSubject.isNotBlank()

    val publicKey: String get() = vapidPublicKey

    fun send(device: PushDevice, payloadJson: String, ttlSeconds: Long = 60): PushDeliveryStatus {
        if (!isConfigured) {
            log.warn("[push] VAPID nie jest skonfigurowane (webpush.vapid.*) — pomijam wysyłkę")
            return PushDeliveryStatus.FAILED
        }

        return try {
            val body = WebPushCrypto.encrypt(
                plaintext = payloadJson.toByteArray(StandardCharsets.UTF_8),
                p256dhB64 = device.p256dh,
                authB64 = device.auth
            )
            val authorization = WebPushCrypto.vapidAuthorizationHeader(
                endpoint = device.endpoint,
                publicKeyB64 = vapidPublicKey,
                privateKeyB64 = vapidPrivateKey,
                subject = vapidSubject
            )

            val request = HttpRequest.newBuilder(URI(device.endpoint))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", authorization)
                .header("Content-Encoding", "aes128gcm")
                .header("Content-Type", "application/octet-stream")
                .header("TTL", ttlSeconds.toString())
                .header("Urgency", "high")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build()

            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            when (response.statusCode()) {
                in 200..299 -> PushDeliveryStatus.DELIVERED
                404, 410 -> {
                    log.info("[push] Subskrypcja wygasła ({}), urządzenie {}", response.statusCode(), device.id)
                    PushDeliveryStatus.SUBSCRIPTION_GONE
                }
                else -> {
                    log.warn(
                        "[push] Push service odrzucił wysyłkę: {} {} (urządzenie {})",
                        response.statusCode(), response.body().take(300), device.id
                    )
                    PushDeliveryStatus.FAILED
                }
            }
        } catch (e: Exception) {
            log.warn("[push] Błąd wysyłki push do urządzenia {}: {}", device.id, e.message)
            PushDeliveryStatus.FAILED
        }
    }
}
