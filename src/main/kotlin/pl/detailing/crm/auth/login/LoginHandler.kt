package pl.detailing.crm.auth.login

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.security.web.context.SecurityContextRepository
import pl.detailing.crm.auth.UnifiedAuthResponse
import pl.detailing.crm.auth.UserData
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.shared.UnauthorizedException
import pl.detailing.crm.subscription.SubscriptionService
import pl.detailing.crm.user.infrastructure.UserRepository

@Service
class LoginHandler(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val subscriptionService: SubscriptionService
) {

    suspend fun handle(request: LoginRequest): Pair<UnifiedAuthResponse, UserPrincipal> =
        withContext(Dispatchers.IO) {
            val email = request.email.lowercase().trim()

            val userEntity = userRepository.findByEmail(email)
                ?: throw UnauthorizedException("Invalid email or password")

            if (!userEntity.isActive) {
                throw UnauthorizedException("Account is inactive")
            }

            if (!passwordEncoder.matches(request.password, userEntity.passwordHash)) {
                throw UnauthorizedException("Invalid email or password")
            }

            val user = userEntity.toDomain()
            val subscriptionInfo = subscriptionService.getSubscriptionInfo(user.studioId)

            val userPrincipal = UserPrincipal(
                userId = user.id,
                studioId = user.studioId,
                role = user.role,
                email = user.email,
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
                    role = user.role.name,
                    subscriptionStatus = subscriptionInfo.status,
                    trialDaysRemaining = subscriptionInfo.daysRemaining
                )
            )

            Pair(response, userPrincipal)
        }
}