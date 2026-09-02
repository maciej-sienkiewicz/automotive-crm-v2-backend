package pl.detailing.crm.auth.login

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import pl.detailing.crm.auth.UnifiedAuthResponse
import pl.detailing.crm.auth.UserData
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.observability.MetricsTags
import pl.detailing.crm.role.permission.PermissionCheckService
import pl.detailing.crm.shared.UnauthorizedException
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.subscription.SubscriptionService
import pl.detailing.crm.user.infrastructure.UserRepository

@Service
class LoginHandler(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val subscriptionService: SubscriptionService,
    private val accountLockoutService: AccountLockoutService,
    private val meterRegistry: MeterRegistry,
    private val permissionCheckService: PermissionCheckService,
    private val studioSettingsRepository: StudioSettingsRepository
) {

    suspend fun handle(request: LoginRequest): Pair<UnifiedAuthResponse, UserPrincipal> =
        withContext(Dispatchers.IO) {
            val email = request.email.lowercase().trim()

            // Check account lockout before any DB lookup to short-circuit quickly
            if (accountLockoutService.isLocked(email)) {
                recordAttempt("blocked")
                // Return the same generic error to avoid revealing lockout status
                throw UnauthorizedException("Nieprawidłowy adres e-mail lub hasło")
            }

            val userEntity = userRepository.findByEmail(email)
            val passwordMatches = userEntity != null &&
                passwordEncoder.matches(request.password, userEntity.passwordHash)

            if (!passwordMatches) {
                // Only track attempts for existing emails to prevent account-lock DoS
                // against addresses that were never registered
                if (userEntity != null) {
                    handleFailedAttempt(email)
                } else {
                    recordAttempt("failure")
                }
                throw UnauthorizedException("Nieprawidłowy adres e-mail lub hasło")
            }

            if (!userEntity!!.isActive) {
                throw UnauthorizedException("Konto jest nieaktywne")
            }

            // Clear failed-attempt counter on successful authentication
            accountLockoutService.clear(email)
            recordAttempt("success")

            val user = userEntity.toDomain()
            val subscriptionInfo = subscriptionService.getSubscriptionInfo(user.studioId)

            val userPrincipal = UserPrincipal(
                userId = user.id,
                studioId = user.studioId,
                isOwner = user.isOwner,
                email = user.email,
                phoneNumber = user.phoneNumber,
                fullName = "${user.firstName} ${user.lastName}"
            )

            val response = UnifiedAuthResponse(
                success = true,
                message = "Login successful",
                redirectUrl = "/dashboard",
                user = UserData(
                    userId = user.id.toString(),
                    studioId = user.studioId.toString(),
                    email = user.email,
                    phoneNumber = user.phoneNumber,
                    role = if (user.isOwner) "OWNER" else "USER",
                    subscriptionStatus = subscriptionInfo.status,
                    daysRemaining = subscriptionInfo.daysRemaining,
                    subscriptionEndsAt = subscriptionInfo.subscriptionEndsAt?.toString(),
                    trialEndsAt = subscriptionInfo.trialEndsAt?.toString(),
                    firstName = user.firstName,
                    lastName = user.lastName,
                    // null = owner (unrestricted). Included here so the UI can hide
                    // inaccessible modules immediately after login, before /auth/me.
                    permissions = permissionCheckService
                        .getPermissions(user.id, user.studioId)
                        ?.map { it.name },
                    trackWorkTime = permissionCheckService
                        .getTrackWorkTime(user.id, user.studioId),
                    idleTimeoutSeconds = withContext(Dispatchers.IO) {
                        studioSettingsRepository.findById(user.studioId.value).orElse(null)?.idleTimeoutSeconds ?: 0
                    }
                )
            )

            Pair(response, userPrincipal)
        }

    private fun handleFailedAttempt(email: String) {
        if (accountLockoutService.recordFailure(email)) {
            recordAttempt("locked")
        } else {
            recordAttempt("failure")
        }
    }

    private fun recordAttempt(result: String) {
        meterRegistry.counter(MetricsTags.SECURITY_LOGIN_ATTEMPTS, "result", result).increment()
    }
}