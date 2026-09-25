package pl.detailing.crm.push.test

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.push.infrastructure.PushDeviceRepository
import pl.detailing.crm.push.infrastructure.sha256Hex
import pl.detailing.crm.push.notify.PushIcon
import pl.detailing.crm.push.notify.PushNotificationType
import pl.detailing.crm.push.notify.PushPayload
import pl.detailing.crm.push.send.PushDeliveryStatus
import pl.detailing.crm.push.send.WebPushSender
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UnprocessableEntityException
import pl.detailing.crm.shared.UserId
import java.time.Instant

data class SendTestPushCommand(
    val studioId: StudioId,
    val userId: UserId,
    /** The subscription endpoint of the device asking — `pushManager.getSubscription()`. */
    val endpoint: String
)

/**
 * „Wyślij powiadomienie próbne" from the pairing wizard.
 *
 * Switching notifications on proves only that the browser handed out a
 * subscription. Whether a real notification then reaches the lock screen
 * depends on four more links nobody can see from the page: our VAPID keys,
 * the push service (FCM / Apple / Mozilla), the Service Worker's `push`
 * handler, and the phone's own settings (Focus, battery saver, a muted app).
 * Users who never saw a notification assumed the whole feature was broken —
 * and support had no way to tell which link failed. A test push walks the
 * exact production path, so "it arrived" is a real guarantee, and a failure
 * comes back as a status the wizard can explain.
 *
 * Addressed by ENDPOINT, not device id: the page knows its own live
 * subscription, while the row id is lost on every reload.
 */
@Service
class SendTestPushHandler(
    private val pushDeviceRepository: PushDeviceRepository,
    private val webPushSender: WebPushSender,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(SendTestPushHandler::class.java)

    @Transactional
    suspend fun handle(command: SendTestPushCommand) = withContext(Dispatchers.IO) {
        if (!webPushSender.isConfigured) {
            throw UnprocessableEntityException("Powiadomienia push nie są skonfigurowane na serwerze")
        }

        // Ownership is part of the lookup: an endpoint registered by someone else
        // is indistinguishable from one that does not exist.
        val device = pushDeviceRepository.findByEndpointHash(sha256Hex(command.endpoint))
            ?.takeIf { it.studioId == command.studioId.value && it.userId == command.userId.value && it.revokedAt == null }
            ?: throw NotFoundException("To urządzenie nie jest sparowane. Włącz powiadomienia ponownie.")

        val payload = objectMapper.writeValueAsString(
            PushPayload(
                type = PushNotificationType.TEST,
                title = "Powiadomienia działają",
                body = "Tak będą wyglądać wiadomości z CRM na tym urządzeniu.",
                url = "/call-device",
                icon = PushIcon.APP,
                tag = "push-test"
            )
        )

        when (webPushSender.send(device.toDomain(), payload, ttlSeconds = 60)) {
            PushDeliveryStatus.DELIVERED -> {
                device.lastUsedAt = Instant.now()
                pushDeviceRepository.save(device)
            }
            PushDeliveryStatus.SUBSCRIPTION_GONE -> {
                device.revokedAt = Instant.now()
                pushDeviceRepository.save(device)
                throw UnprocessableEntityException(
                    "Subskrypcja tego urządzenia wygasła. Wyłącz i włącz powiadomienia ponownie."
                )
            }
            PushDeliveryStatus.FAILED -> throw UnprocessableEntityException(
                "Serwer powiadomień nie przyjął wiadomości. Spróbuj ponownie za chwilę."
            )
        }
        log.info("[push] Powiadomienie próbne: deviceId={}, userId={}", device.id, command.userId.value)
    }
}
