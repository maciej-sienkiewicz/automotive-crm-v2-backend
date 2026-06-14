package pl.detailing.crm.auth.passwordreset

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.auth.PasswordPolicy
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.user.infrastructure.UserRepository

/**
 * Completes a password reset: verifies the one-time token, enforces the password
 * policy, stores the new BCrypt hash and clears any login lockout for the account.
 */
@Service
class ResetPasswordHandler(
    private val userRepository: UserRepository,
    private val tokenService: PasswordResetTokenService,
    private val passwordEncoder: PasswordEncoder,
    private val passwordPolicy: PasswordPolicy,
    private val redisTemplate: StringRedisTemplate
) {
    companion object {
        // Mirrors the keys used by LoginHandler so a successful reset also lifts any lockout.
        private const val LOCKOUT_KEY_PREFIX = "auth:lockout:"
        private const val ATTEMPTS_KEY_PREFIX = "auth:attempts:"
    }

    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    suspend fun handle(request: ResetPasswordRequest): Unit = withContext(Dispatchers.IO) {
        // Validate the password first so a weak password does not burn the token.
        passwordPolicy.validate(request.password, request.confirmPassword)

        val userId = tokenService.consumeToken(request.token)
            ?: throw ValidationException("Link do resetowania hasła jest nieprawidłowy lub wygasł")

        val userEntity = userRepository.findById(userId).orElse(null)
            ?: throw ValidationException("Link do resetowania hasła jest nieprawidłowy lub wygasł")

        userEntity.passwordHash = passwordEncoder.encode(request.password)
        userRepository.save(userEntity)

        // The user just proved control of their inbox — clear any active lockout.
        val email = userEntity.email.lowercase().trim()
        redisTemplate.delete("$LOCKOUT_KEY_PREFIX$email")
        redisTemplate.delete("$ATTEMPTS_KEY_PREFIX$email")

        logger.info("Password successfully reset [userId={}]", userEntity.id)
    }
}
