package pl.detailing.crm.auth.signup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.UserRole
import pl.detailing.crm.subscription.SubscriptionService
import pl.detailing.crm.user.domain.User
import pl.detailing.crm.user.infrastructure.UserEntity
import pl.detailing.crm.user.infrastructure.UserRepository
import pl.detailing.crm.voice.MobileTokenService
import java.time.Instant

@Service
class SignupHandler(
    private val validatorComposite: SignupValidatorComposite,
    private val subscriptionService: SubscriptionService,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val mobileTokenService: MobileTokenService
) {

    @Transactional
    suspend fun handle(request: SignupRequest): SignupResult = withContext(Dispatchers.IO) {
        validatorComposite.validate(request)

        val studioName = "${request.firstName.trim()} ${request.lastName.trim()}'s Detailing Studio"

        val command = SignupCommand(
            firstName = request.firstName.trim(),
            lastName = request.lastName.trim(),
            studioName = studioName,
            email = request.email.lowercase().trim(),
            phoneNumber = "+48888915358",
            passwordHash = passwordEncoder.encode(request.password)
        )

        val studio = subscriptionService.createStudio(command.studioName)

        val user = User(
            id = UserId.random(),
            studioId = studio.id,
            email = command.email,
            phoneNumber = command.phoneNumber,
            passwordHash = command.passwordHash,
            firstName = command.firstName,
            lastName = command.lastName,
            role = UserRole.OWNER,
            isActive = true,
            createdAt = Instant.now(),
            mobileToken = mobileTokenService.generateSecureToken()
        )

        val userEntity = UserEntity.fromDomain(user)
        userRepository.save(userEntity)

        SignupResult(
            userId = user.id,
            studioId = studio.id,
            email = user.email,
            phoneNumber = user.phoneNumber,
            firstName = command.firstName,
            lastName = command.lastName
        )
    }
}