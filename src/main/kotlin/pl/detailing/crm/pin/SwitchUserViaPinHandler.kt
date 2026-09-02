package pl.detailing.crm.pin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import pl.detailing.crm.auth.UnifiedAuthResponse
import pl.detailing.crm.auth.UserData
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.role.permission.PermissionCheckService
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.shared.NotFoundException
import pl.detailing.crm.shared.UnauthorizedException
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.subscription.SubscriptionService
import pl.detailing.crm.user.infrastructure.UserRepository
import java.time.Duration
import java.util.UUID

private const val MAX_PIN_ATTEMPTS = 3

/** Klucz Redis licznika nieudanych prób PIN — jeden na (studio, użytkownik docelowy). */
internal fun pinAttemptsKey(studioId: UUID, userId: UUID) = "pin:attempts:$studioId:$userId"

@Service
class SwitchUserViaPinHandler(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val subscriptionService: SubscriptionService,
    private val permissionCheckService: PermissionCheckService,
    private val studioSettingsRepository: StudioSettingsRepository,
    private val redisTemplate: StringRedisTemplate
) {
    companion object {
        /** Okno, w którym liczą się nieudane próby; po nim licznik znika sam. */
        private val ATTEMPTS_WINDOW: Duration = Duration.ofMinutes(15)
    }

    suspend fun handle(
        targetUserId: UUID,
        studioId: UUID,
        pin: String
    ): Pair<UnifiedAuthResponse, UserPrincipal> = withContext(Dispatchers.IO) {
        val user = userRepository.findByIdAndStudioId(targetUserId, studioId)
            ?: throw NotFoundException("Użytkownik nie istnieje w tym studiu")

        if (!user.isActive) {
            throw ForbiddenException("Konto użytkownika jest nieaktywne")
        }

        if (user.pinLocked) {
            throw ForbiddenException("PIN tego użytkownika jest zablokowany po zbyt wielu nieudanych próbach")
        }

        val pinHash = user.pinHash
            ?: throw ForbiddenException("Ten użytkownik nie skonfigurował jeszcze kodu PIN")

        val pinMatches = passwordEncoder.matches(pin, pinHash)

        if (!pinMatches) {
            // Atomic INCR in Redis. The previous read-modify-write on the users row let
            // N parallel requests all observe 0 failures and each write 1 — a 4-digit PIN
            // could be brute-forced through the race before the lock ever engaged.
            val attemptsKey = pinAttemptsKey(studioId, user.id)
            val attempts = redisTemplate.opsForValue().increment(attemptsKey) ?: 1L
            if (attempts == 1L) redisTemplate.expire(attemptsKey, ATTEMPTS_WINDOW)

            user.pinFailedAttempts = attempts.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (attempts >= MAX_PIN_ATTEMPTS) {
                user.pinLocked = true
            }
            userRepository.save(user)

            if (user.pinLocked) {
                throw ForbiddenException("Zbyt wiele nieudanych prób. PIN został zablokowany.")
            }
            throw UnauthorizedException("Nieprawidłowy kod PIN")
        }

        // Success — reset failed attempts
        redisTemplate.delete(pinAttemptsKey(studioId, user.id))
        user.pinFailedAttempts = 0
        userRepository.save(user)

        val userId = UserId(user.id)
        val studioIdTyped = StudioId(user.studioId)
        val subscriptionInfo = subscriptionService.getSubscriptionInfo(studioIdTyped)

        val principal = UserPrincipal(
            userId = userId,
            studioId = studioIdTyped,
            isOwner = user.isOwner,
            email = user.email,
            phoneNumber = user.phoneNumber,
            fullName = "${user.firstName} ${user.lastName}"
        )

        val response = UnifiedAuthResponse(
            success = true,
            message = "Przełączono na konto ${user.firstName} ${user.lastName}",
            redirectUrl = "/dashboard",
            user = UserData(
                userId = userId.toString(),
                studioId = studioIdTyped.toString(),
                email = user.email,
                phoneNumber = user.phoneNumber,
                role = if (user.isOwner) "OWNER" else "USER",
                subscriptionStatus = subscriptionInfo.status,
                daysRemaining = subscriptionInfo.daysRemaining,
                subscriptionEndsAt = subscriptionInfo.subscriptionEndsAt?.toString(),
                trialEndsAt = subscriptionInfo.trialEndsAt?.toString(),
                firstName = user.firstName,
                lastName = user.lastName,
                permissions = permissionCheckService
                    .getPermissions(userId, studioIdTyped)
                    ?.map { it.name },
                trackWorkTime = permissionCheckService
                    .getTrackWorkTime(userId, studioIdTyped),
                idleTimeoutSeconds = studioSettingsRepository
                    .findById(user.studioId).orElse(null)?.idleTimeoutSeconds ?: 0
            )
        )

        Pair(response, principal)
    }
}
