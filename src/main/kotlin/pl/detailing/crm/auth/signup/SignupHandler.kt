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
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Service
class SignupHandler(
    private val validatorComposite: SignupValidatorComposite,
    private val subscriptionService: SubscriptionService,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder
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
            passwordHash = passwordEncoder.encode(request.password)
        )

        val studio = subscriptionService.createStudioWithTrial(command.studioName)

        val user = User(
            id = UserId.random(),
            studioId = studio.id,
            email = command.email,
            passwordHash = command.passwordHash,
            firstName = command.firstName,
            lastName = command.lastName,
            role = UserRole.OWNER,
            isActive = true,
            createdAt = Instant.now()
        )

        val userEntity = UserEntity.fromDomain(user)
        userRepository.save(userEntity)

        val formatter = DateTimeFormatter.ISO_INSTANT
        val trialEndsAtFormatted = studio.trialEndsAt?.atOffset(ZoneOffset.UTC)?.format(formatter)
            ?: Instant.now().toString()

        SignupResult(
            userId = user.id,
            studioId = studio.id,
            email = user.email,
            firstName = command.firstName,
            lastName = command.lastName,
            trialEndsAt = trialEndsAtFormatted
        )
    }
}