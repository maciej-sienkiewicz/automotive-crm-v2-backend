package pl.detailing.crm.push.notify

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.push.infrastructure.PushDeviceRepository
import pl.detailing.crm.push.send.PushDeliveryStatus
import pl.detailing.crm.push.send.WebPushSender
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.PermissionCheckService
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.user.infrastructure.UserRepository
import java.time.Instant

/**
 * Sends a broadcast notification to the phones of everyone in a studio who is
 * allowed to receive it.
 *
 * Permission is checked per RECIPIENT, never per actor: who closed the visit is
 * irrelevant, what matters is who may learn the amount. Owners bypass the check,
 * exactly as `@RequiresPermission` does everywhere else in this codebase.
 *
 * Personal data follows the same rule as every screen: the customer's name and
 * contact reach only recipients who may see them (`CUSTOMERS_VIEW`, owners always).
 * A push goes to one person, so unlike the studio-wide dashboard topic — which is
 * forcibly masked because its audience is mixed — it can be personalised per
 * recipient instead of masked for all. Callers pass the masked payload and,
 * where it differs, the personal one ([PushMessages.Message]).
 *
 * Delivery is best-effort and never rethrows. These notifications ride on top of
 * a business operation that has already committed — a push service having a bad
 * minute must not turn a closed visit into an error, let alone roll anything back.
 */
@Service
class PushNotifier(
    private val pushDeviceRepository: PushDeviceRepository,
    private val userRepository: UserRepository,
    private val permissionCheckService: PermissionCheckService,
    private val webPushSender: WebPushSender,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(PushNotifier::class.java)

    /**
     * @param requiredPermission permission a recipient must hold; null means every
     *        user in the studio who has a paired phone.
     * @param ttlSeconds how long the push service may hold the message. Six hours
     *        by default: these are informational and still make sense after a spell
     *        with no signal — unlike a click-to-call push, worthless a minute later.
     * @param excludeUserId the person whose action this reports. Someone who just
     *        booked a slot or checked a car in does not need their own pocket to tell
     *        them so; the notification is for everyone else.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun broadcast(
        studioId: StudioId,
        requiredPermission: Permission?,
        message: PushMessages.Message,
        ttlSeconds: Long = 6 * 3600,
        excludeUserId: UserId? = null
    ) {
        if (!webPushSender.isConfigured) return

        val devices = pushDeviceRepository.findByStudioIdAndRevokedAtIsNull(studioId.value)
        if (devices.isEmpty()) return

        // One permission check per USER, not per device: someone with a phone and a
        // tablet paired must not cost two identical lookups.
        val recipients = devices.map { it.userId }.distinct()
            .filter { it != excludeUserId?.value }
            .mapNotNull { recipient(UserId(it), studioId, requiredPermission) }
            .associateBy { it.userId }
        if (recipients.isEmpty()) return

        val maskedJson = objectMapper.writeValueAsString(message.masked)
        val personalJson = message.personal?.let(objectMapper::writeValueAsString) ?: maskedJson
        var delivered = 0

        devices.forEach { device ->
            val recipient = recipients[device.userId] ?: return@forEach
            val json = if (recipient.seesPersonalData) personalJson else maskedJson
            when (webPushSender.send(device.toDomain(), json, ttlSeconds)) {
                PushDeliveryStatus.DELIVERED -> {
                    delivered++
                    device.lastUsedAt = Instant.now()
                    pushDeviceRepository.save(device)
                }
                PushDeliveryStatus.SUBSCRIPTION_GONE -> {
                    device.revokedAt = Instant.now()
                    pushDeviceRepository.save(device)
                }
                PushDeliveryStatus.FAILED -> Unit
            }
        }

        log.info(
            "[push] {}: odbiorcow={}, urzadzen={}, dostarczono={}",
            message.masked.type, recipients.size, devices.size, delivered
        )
    }

    /**
     * Powiadomienie dla JEDNEJ osoby — na wszystkie jej sparowane urządzenia, o ile
     * konto jest aktywne i ma choć jedno z [anyOf] (właściciel zawsze). Dla powiadomień,
     * które ktoś sam dla siebie włączył („Dostępny nowy raport"), a nie dla zdarzeń,
     * o których ma wiedzieć każdy uprawniony w studiu.
     *
     * @return true, gdy dotarło na co najmniej jedno urządzenie.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun notifyUser(
        studioId: StudioId,
        userId: UserId,
        anyOf: List<Permission>,
        payload: PushPayload,
        ttlSeconds: Long = 6 * 3600
    ): Boolean {
        if (!webPushSender.isConfigured) return false
        // Bez danych klientów w treści, więc jedna wersja dla każdego odbiorcy —
        // [recipient] rozstrzyga tylko, czy w ogóle wolno mu to dostać.
        if (anyOf.none { recipient(userId, studioId, it) != null }) return false

        val devices = pushDeviceRepository.findByStudioIdAndUserIdAndRevokedAtIsNull(studioId.value, userId.value)
        if (devices.isEmpty()) return false

        val json = objectMapper.writeValueAsString(payload)
        var delivered = 0
        devices.forEach { device ->
            when (webPushSender.send(device.toDomain(), json, ttlSeconds)) {
                PushDeliveryStatus.DELIVERED -> {
                    delivered++
                    device.lastUsedAt = Instant.now()
                    pushDeviceRepository.save(device)
                }
                PushDeliveryStatus.SUBSCRIPTION_GONE -> {
                    device.revokedAt = Instant.now()
                    pushDeviceRepository.save(device)
                }
                PushDeliveryStatus.FAILED -> Unit
            }
        }
        log.info("[push] {}: uzytkownik={}, urzadzen={}, dostarczono={}", payload.type, userId.value, devices.size, delivered)
        return delivered > 0
    }

    private data class Recipient(val userId: java.util.UUID, val seesPersonalData: Boolean)

    private fun recipient(userId: UserId, studioId: StudioId, permission: Permission?): Recipient? {
        val user = userRepository.findByIdAndStudioId(userId.value, studioId.value) ?: return null
        // A deactivated account keeps its rows, including paired devices. It must not
        // keep receiving the studio's revenue on a phone that already walked out.
        if (!user.isActive) return null
        if (user.isOwner) return Recipient(userId.value, seesPersonalData = true)
        if (permission != null && !permissionCheckService.hasPermission(userId, studioId, permission)) return null
        return Recipient(
            userId.value,
            seesPersonalData = permissionCheckService.hasPermission(userId, studioId, Permission.CUSTOMERS_VIEW)
        )
    }
}
