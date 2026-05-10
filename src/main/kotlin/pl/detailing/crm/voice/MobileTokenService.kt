package pl.detailing.crm.voice

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.user.infrastructure.UserEntity
import pl.detailing.crm.user.infrastructure.UserRepository
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

@Service
class MobileTokenService(
    private val userRepository: UserRepository
) {
    private val secureRandom = SecureRandom()

    fun resolveToken(token: String): UserEntity? {
        val user = userRepository.findByMobileToken(token) ?: return null
        return if (user.isActive) user else null
    }

    @Transactional
    fun generateToken(userId: UUID): String {
        val user = userRepository.findById(userId).orElseThrow {
            IllegalArgumentException("User not found: $userId")
        }
        val token = generateSecureToken()
        user.mobileToken = token
        userRepository.save(user)
        return token
    }

    private fun generateSecureToken(): String {
        val bytes = ByteArray(48)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
