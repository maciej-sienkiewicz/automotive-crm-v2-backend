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
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun broadcast(
        studioId: StudioId,
        requiredPermission: Permission?,
        payload: PushPayload,
        ttlSeconds: Long = 6 * 3600
    ) {
        if (!webPushSender.isConfigured) return

        val devices = pushDeviceRepository.findByStudioIdAndRevokedAtIsNull(studioId.value)
        if (devices.isEmpty()) return

        // One permission check per USER, not per device: someone with a phone and a
        // tablet paired must not cost two identical lookups.
        val allowedUserIds = devices.map { it.userId }.distinct()
            .filter { canReceive(UserId(it), studioId, requiredPermission) }
            .toSet()
        if (allowedUserIds.isEmpty()) return

        val json = objectMapper.writeValueAsString(payload)
        var delivered = 0

        devices.filter { it.userId in allowedUserIds }.forEach { device ->
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
            payload.type, allowedUserIds.size, devices.size, delivered
        )
    }

    private fun canReceive(userId: UserId, studioId: StudioId, permission: Permission?): Boolean {
        val user = userRepository.findByIdAndStudioId(userId.value, studioId.value) ?: return false
        // A deactivated account keeps its rows, including paired devices. It must not
        // keep receiving the studio's revenue on a phone that already walked out.
        if (!user.isActive) return false
        if (user.isOwner) return true
        return permission == null || permissionCheckService.hasPermission(userId, studioId, permission)
    }
}
