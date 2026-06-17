package pl.detailing.crm.demo

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.auth.UserPrincipal
import pl.detailing.crm.shared.*
import pl.detailing.crm.studio.infrastructure.StudioEntity
import pl.detailing.crm.studio.infrastructure.StudioRepository
import pl.detailing.crm.user.infrastructure.UserEntity
import pl.detailing.crm.user.infrastructure.UserRepository
import pl.detailing.crm.voice.MobileTokenService
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class DemoAccountService(
    private val demoAccountRepository: DemoAccountRepository,
    private val demoDataInitializer: DemoDataInitializer,
    private val studioRepository: StudioRepository,
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
    private val mobileTokenService: MobileTokenService,
    private val securityContextRepository: SecurityContextRepository
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val DEMO_TTL_HOURS = 2L
        private const val DEMO_PASSWORD = "Demo123!"
        private const val DEMO_STUDIO_NAME = "Moje Studio Detailingowe (DEMO)"
    }

    @Transactional
    fun createDemoAccount(request: HttpServletRequest, response: HttpServletResponse): DemoAccountResult {
        val studioId = StudioId.random()
        val userId = UserId.random()
        val uniqueSuffix = UUID.randomUUID().toString().replace("-", "").take(8)
        val email = "demo-$uniqueSuffix@demo.detailboost.pl"
        val expiresAt = Instant.now().plus(DEMO_TTL_HOURS, ChronoUnit.HOURS)
        val now = Instant.now()

        logger.info("Creating demo account: studioId={}, email={}", studioId, email)

        val studioEntity = StudioEntity(
            id = studioId.value,
            name = DEMO_STUDIO_NAME,
            subscriptionStatus = SubscriptionStatus.TRIALING,
            trialEndsAt = now.plus(60, ChronoUnit.DAYS),
            subscriptionEndsAt = null,
            trialUsed = true,
            createdAt = now,
            emailAlias = UUID.randomUUID().toString().replace("-", "").take(32)
        )
        studioRepository.save(studioEntity)

        val userEntity = UserEntity(
            id = userId.value,
            studioId = studioId.value,
            email = email,
            phoneNumber = "+48000000000",
            passwordHash = passwordEncoder.encode(DEMO_PASSWORD),
            firstName = "Demo",
            lastName = "User",
            isOwner = true,
            isActive = true,
            createdAt = now,
            mobileToken = mobileTokenService.generateSecureToken()
        )
        userRepository.save(userEntity)

        demoAccountRepository.save(
            DemoAccountEntity(
                id = UUID.randomUUID(),
                studioId = studioId.value,
                userId = userId.value,
                email = email,
                expiresAt = expiresAt,
                createdAt = now
            )
        )

        demoDataInitializer.seed(studioId.value, userId.value)

        val userPrincipal = UserPrincipal(
            userId = userId,
            studioId = studioId,
            isOwner = true,
            email = email,
            phoneNumber = "+48000000000",
            fullName = "Demo User"
        )
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = userPrincipal
        SecurityContextHolder.setContext(context)
        securityContextRepository.saveContext(context, request, response)

        logger.info("Demo account created successfully: studioId={}", studioId)

        return DemoAccountResult(
            email = email,
            password = DEMO_PASSWORD,
            studioId = studioId.toString(),
            userId = userId.toString(),
            expiresAt = expiresAt
        )
    }
}

data class DemoAccountResult(
    val email: String,
    val password: String,
    val studioId: String,
    val userId: String,
    val expiresAt: Instant
)
