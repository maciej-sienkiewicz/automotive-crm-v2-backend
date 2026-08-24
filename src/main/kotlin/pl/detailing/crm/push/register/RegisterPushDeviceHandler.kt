package pl.detailing.crm.push.register

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.push.domain.PushDevice
import pl.detailing.crm.push.infrastructure.PushDeviceEntity
import pl.detailing.crm.push.infrastructure.PushDeviceRepository
import pl.detailing.crm.shared.ValidationException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * Registers (or refreshes) a device's Web Push subscription.
 *
 * The endpoint is the identity of the subscription, not the device row id:
 * browsers rotate subscriptions (pushsubscriptionchange, permission re-grant,
 * PWA reinstall), and the same physical phone may come back with a brand-new
 * endpoint. Upsert-by-endpoint keeps one row per living subscription; a
 * re-register of a revoked endpoint simply reactivates it — the user pressing
 * "włącz powiadomienia" again IS the consent that revocation removed.
 */
@Service
class RegisterPushDeviceHandler(
    private val pushDeviceRepository: PushDeviceRepository
) {
    private val log = LoggerFactory.getLogger(RegisterPushDeviceHandler::class.java)

    @Transactional
    suspend fun handle(command: RegisterPushDeviceCommand): PushDevice =
        withContext(Dispatchers.IO) {
            if (command.endpoint.isBlank() || !command.endpoint.startsWith("https://")) {
                throw ValidationException("Nieprawidłowy endpoint subskrypcji push")
            }
            if (command.p256dh.isBlank() || command.auth.isBlank()) {
                throw ValidationException("Subskrypcja push nie zawiera kluczy szyfrowania")
            }

            val endpointHash = sha256Hex(command.endpoint)
            val existing = pushDeviceRepository.findByEndpointHash(endpointHash)

            val entity = if (existing != null) {
                // The endpoint is issued per browser profile, so a hash match for a
                // different user means that user logged in on the same phone —
                // ownership follows the current session.
                if (existing.userId != command.userId.value || existing.studioId != command.studioId.value) {
                    pushDeviceRepository.delete(existing)
                    pushDeviceRepository.flush()
                    newEntity(command, endpointHash)
                } else {
                    existing.apply {
                        deviceName = command.deviceName.trim().take(200)
                        userAgent = command.userAgent?.take(400)
                        p256dh = command.p256dh
                        auth = command.auth
                        revokedAt = null
                    }
                }
            } else {
                newEntity(command, endpointHash)
            }

            val saved = pushDeviceRepository.save(entity)
            log.info(
                "[push] Zarejestrowano urządzenie push: deviceId={}, userId={}, studioId={}",
                saved.id, command.userId.value, command.studioId.value
            )
            saved.toDomain()
        }

    private fun newEntity(command: RegisterPushDeviceCommand, endpointHash: String) = PushDeviceEntity(
        id = UUID.randomUUID(),
        studioId = command.studioId.value,
        userId = command.userId.value,
        deviceName = command.deviceName.trim().take(200).ifBlank { "Telefon" },
        userAgent = command.userAgent?.take(400),
        endpoint = command.endpoint,
        endpointHash = endpointHash,
        p256dh = command.p256dh,
        auth = command.auth,
        createdAt = Instant.now()
    )

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
