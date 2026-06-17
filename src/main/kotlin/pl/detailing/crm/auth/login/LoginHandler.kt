package pl.detailing.crm.auth.login

import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import pl.detailing.crm.auth.UnifiedAuthResponse
import pl.detailing.crm.auth.UserData
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.observability.MetricsTags
import pl.detailing.crm.shared.UnauthorizedException
import pl.detailing.crm.subscription.SubscriptionService
import pl.detailing.crm.user.infrastructure.UserRepository
import java.time.Duration
import java.time.Instant

@Service
class LoginHandler(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val subscriptionService: SubscriptionService,
    private val redisTemplate: StringRedisTemplate,
    private val meterRegistry: MeterRegistry
) {
    companion object {
        private const val LOCKOUT_KEY_PREFIX  = "auth:lockout:"
        private const val ATTEMPTS_KEY_PREFIX = "auth:attempts:"
        private const val MAX_ATTEMPTS = 5
        private val LOCKOUT_DURATION   = Duration.ofMinutes(15)
        private val ATTEMPTS_WINDOW    = Duration.ofMinutes(15)
    }

    suspend fun handle(request: LoginRequest): Pair<UnifiedAuthResponse, UserPrincipal> =
        withContext(Dispatchers.IO) {
            val email = request.email.lowercase().trim()

            // Check account lockout before any DB lookup to short-circuit quickly
            if (redisTemplate.hasKey("$LOCKOUT_KEY_PREFIX$email") == true) {
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
            redisTemplate.delete("$ATTEMPTS_KEY_PREFIX$email")
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
                    lastName = user.lastName
                )
            )

            Pair(response, userPrincipal)
        }

    private fun handleFailedAttempt(email: String) {
        val attemptsKey = "$ATTEMPTS_KEY_PREFIX$email"
        val attempts = redisTemplate.opsForValue().increment(attemptsKey) ?: 1L
        if (attempts == 1L) redisTemplate.expire(attemptsKey, ATTEMPTS_WINDOW)

        if (attempts >= MAX_ATTEMPTS) {
            redisTemplate.opsForValue().set(
                "$LOCKOUT_KEY_PREFIX$email",
                Instant.now().toString(),
                LOCKOUT_DURATION
            )
            redisTemplate.delete(attemptsKey)
            recordAttempt("locked")
        } else {
            recordAttempt("failure")
        }
    }

    private fun recordAttempt(result: String) {
        meterRegistry.counter(MetricsTags.SECURITY_LOGIN_ATTEMPTS, "result", result).increment()
    }
}