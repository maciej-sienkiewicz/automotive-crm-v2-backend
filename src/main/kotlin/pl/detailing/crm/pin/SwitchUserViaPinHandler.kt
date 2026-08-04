package pl.detailing.crm.pin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import pl.detailing.crm.subscription.SubscriptionService
import pl.detailing.crm.user.infrastructure.UserRepository
import java.util.UUID

private const val MAX_PIN_ATTEMPTS = 3

@Service
class SwitchUserViaPinHandler(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val subscriptionService: SubscriptionService,
    private val permissionCheckService: PermissionCheckService
) {
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
            val newAttempts = user.pinFailedAttempts + 1
            user.pinFailedAttempts = newAttempts
            if (newAttempts >= MAX_PIN_ATTEMPTS) {
                user.pinLocked = true
            }
            userRepository.save(user)

            if (user.pinLocked) {
                throw ForbiddenException("Zbyt wiele nieudanych prób. PIN został zablokowany.")
            }
            throw UnauthorizedException("Nieprawidłowy kod PIN")
        }

        // Success — reset failed attempts
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
                    .getTrackWorkTime(userId, studioIdTyped)
            )
        )

        Pair(response, principal)
    }
}
