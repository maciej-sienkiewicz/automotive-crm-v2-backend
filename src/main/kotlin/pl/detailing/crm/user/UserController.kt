package pl.detailing.crm.user

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.voice.MobileTokenResponse
import pl.detailing.crm.voice.MobileTokenService

private const val MOBILE_BASE_URL = "https://detailboost.pl/mobile/voice-commands/index.html"

@RestController
@RequestMapping("/api/v1/users")
class UserController(
    private val mobileTokenService: MobileTokenService
) {

    @PostMapping("/me/mobile-token")
    fun generateMobileToken(): ResponseEntity<MobileTokenResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val token = mobileTokenService.generateToken(principal.userId.value)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            MobileTokenResponse(
                token = token,
                url = "$MOBILE_BASE_URL?token=$token"
            )
        )
    }
}
