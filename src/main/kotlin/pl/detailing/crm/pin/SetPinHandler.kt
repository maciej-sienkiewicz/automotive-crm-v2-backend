package pl.detailing.crm.pin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import pl.detailing.crm.shared.UnauthorizedException
import pl.detailing.crm.shared.ValidationException
import pl.detailing.crm.user.infrastructure.UserRepository
import java.util.UUID

private val PIN_REGEX = Regex("^\\d{4}$")

@Service
class SetPinHandler(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder
) {
    suspend fun handle(
        userId: UUID,
        studioId: UUID,
        currentPassword: String,
        newPin: String
    ) = withContext(Dispatchers.IO) {
        if (!PIN_REGEX.matches(newPin)) {
            throw ValidationException("Kod PIN musi składać się z dokładnie 4 cyfr")
        }

        val user = userRepository.findByIdAndStudioId(userId, studioId)
            ?: throw UnauthorizedException("Nie znaleziono użytkownika")

        if (!passwordEncoder.matches(currentPassword, user.passwordHash)) {
            throw UnauthorizedException("Nieprawidłowe hasło")
        }

        user.pinHash = passwordEncoder.encode(newPin)
        user.pinLocked = false
        user.pinFailedAttempts = 0
        userRepository.save(user)
    }
}
