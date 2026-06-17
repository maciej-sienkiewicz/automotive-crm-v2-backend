// src/main/kotlin/pl/detailing/crm/auth/AuthController.kt

package pl.detailing.crm.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.login.LoginHandler
import pl.detailing.crm.auth.login.LoginRequest
import pl.detailing.crm.auth.signup.SignupHandler
import pl.detailing.crm.auth.signup.SignupRequest
import pl.detailing.crm.subscription.SubscriptionService
import pl.detailing.crm.user.infrastructure.UserRepository
import pl.detailing.crm.voice.MobileTokenService

@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val signupHandler: SignupHandler,
    private val loginHandler: LoginHandler,
    private val subscriptionService: SubscriptionService,
    private val securityContextRepository: SecurityContextRepository,
    private val userRepository: UserRepository,
    private val mobileTokenService: MobileTokenService
) {

    @PostMapping("/signup")
    fun signup(@RequestBody request: SignupRequest): ResponseEntity<UnifiedAuthResponse> = runBlocking {
        val result = signupHandler.handle(request)

        ResponseEntity
            .status(HttpStatus.CREATED)
            .body(UnifiedAuthResponse(
                success = true,
                message = "Account created successfully. Trial period: 60 days",
                redirectUrl = "/onboarding",
                user = UserData(
                    userId = result.userId.toString(),
                    studioId = result.studioId.toString(),
                    email = result.email,
                    phoneNumber = result.phoneNumber,
                    role = "OWNER",
                    subscriptionStatus = pl.detailing.crm.shared.SubscriptionStatus.NO_PLAN,
                    daysRemaining = null,
                    subscriptionEndsAt = null,
                    trialEndsAt = null,
                    firstName = result.firstName,
                    lastName = result.lastName
                )
            ))
    }

    @PostMapping("/login")
    fun login(
        @RequestBody request: LoginRequest,
        httpRequest: HttpServletRequest,
        httpResponse: HttpServletResponse
    ): ResponseEntity<UnifiedAuthResponse> = runBlocking {
        val (response, userPrincipal) = loginHandler.handle(request)

        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = userPrincipal
        SecurityContextHolder.setContext(context)

        securityContextRepository.saveContext(context, httpRequest, httpResponse)

        ResponseEntity.ok(response)
    }

    @PostMapping("/logout")
    fun logout(
        httpRequest: HttpServletRequest,
        httpResponse: HttpServletResponse
    ): ResponseEntity<UnifiedAuthResponse> {
        val context = SecurityContextHolder.createEmptyContext()
        securityContextRepository.saveContext(context, httpRequest, httpResponse)

        httpRequest.session.invalidate()
        SecurityContextHolder.clearContext()

        return ResponseEntity.ok(UnifiedAuthResponse(
            success = true,
            message = "Logged out successfully"
        ))
    }

    @GetMapping("/me")
    fun getCurrentUser(): ResponseEntity<UnifiedAuthResponse> = runBlocking {
        try {
            val principal = SecurityContextHelper.getCurrentUser()
            val subscriptionInfo = subscriptionService.getSubscriptionInfo(principal.studioId)

            val userEntity = userRepository.findById(principal.userId.value).orElse(null)
            val mobileToken = userEntity?.let { mobileTokenService.ensureToken(it) }

            ResponseEntity.ok(UnifiedAuthResponse(
                success = true,
                user = UserData(
                    userId = principal.userId.toString(),
                    studioId = principal.studioId.toString(),
                    email = principal.email,
                    phoneNumber = principal.phoneNumber,
                    role = if (principal.isOwner) "OWNER" else "USER",
                    subscriptionStatus = subscriptionInfo.status,
                    daysRemaining = subscriptionInfo.daysRemaining,
                    subscriptionEndsAt = subscriptionInfo.subscriptionEndsAt?.toString(),
                    trialEndsAt = subscriptionInfo.trialEndsAt?.toString(),
                    firstName = principal.fullName.split(" ").first(),
                    lastName = principal.fullName.split(" ").last(),
                    mobileToken = mobileToken
                )
            ))
        } catch (e: Exception) {
            ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                UnifiedAuthResponse(
                    success = false,
                    message = "Not authenticated"
                )
            )
        }
    }
}