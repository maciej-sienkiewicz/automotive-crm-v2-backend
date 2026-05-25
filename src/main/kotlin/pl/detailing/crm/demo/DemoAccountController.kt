package pl.detailing.crm.demo

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.UnifiedAuthResponse
import pl.detailing.crm.auth.UserData
import pl.detailing.crm.shared.SubscriptionStatus
import java.time.Instant

@RestController
@RequestMapping("/api/v1/demo")
class DemoAccountController(
    private val demoAccountService: DemoAccountService
) {

    /**
     * Creates an isolated demo account pre-seeded with realistic Polish detailing studio data.
     * The account is automatically deleted after 2 hours.
     * Each call creates a completely independent account – concurrent demo users cannot interfere.
     */
    @PostMapping
    fun createDemoAccount(
        request: HttpServletRequest,
        response: HttpServletResponse
    ): ResponseEntity<DemoAccountResponse> {
        val result = demoAccountService.createDemoAccount(request, response)

        return ResponseEntity.status(HttpStatus.CREATED).body(
            DemoAccountResponse(
                success = true,
                message = "Konto DEMO zostało utworzone. Zostanie automatycznie usunięte za 2 godziny.",
                expiresAt = result.expiresAt,
                auth = UnifiedAuthResponse(
                    success = true,
                    message = "Zalogowano do konta DEMO",
                    redirectUrl = "/dashboard",
                    user = UserData(
                        userId = result.userId,
                        studioId = result.studioId,
                        email = result.email,
                        phoneNumber = "+48000000000",
                        role = "OWNER",
                        subscriptionStatus = SubscriptionStatus.TRIALING,
                        daysRemaining = 60,
                        subscriptionEndsAt = null,
                        trialEndsAt = result.expiresAt.toString(),
                        firstName = "Demo",
                        lastName = "User",
                        mobileToken = null
                    )
                )
            )
        )
    }
}

data class DemoAccountResponse(
    val success: Boolean,
    val message: String,
    val expiresAt: Instant,
    val auth: UnifiedAuthResponse
)
