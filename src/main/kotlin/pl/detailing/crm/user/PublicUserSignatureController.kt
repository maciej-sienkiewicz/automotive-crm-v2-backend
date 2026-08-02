package pl.detailing.crm.user

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.user.signature.UserSignatureLinkService

/**
 * Session-free endpoints for drawing a personal signature on the user's own phone.
 *
 * Authentication is the unguessable, TTL-bound token delivered by SMS —
 * no JSESSIONID required. Registered as permitAll in SecurityConfig.
 *
 *  GET  /api/public/user-signature/{token}         → session validity check
 *  POST /api/public/user-signature/{token}/submit  → save the drawn signature
 */
@RestController
@RequestMapping("/api/public/user-signature")
class PublicUserSignatureController(
    private val userSignatureLinkService: UserSignatureLinkService
) {

    @GetMapping("/{token}")
    fun getStatus(@PathVariable token: String): ResponseEntity<UserSignatureSessionDto> {
        val valid = userSignatureLinkService.isValid(token)
        return ResponseEntity.ok(
            UserSignatureSessionDto(status = if (valid) "PENDING" else "EXPIRED")
        )
    }

    @PostMapping("/{token}/submit")
    fun submit(
        @PathVariable token: String,
        @RequestBody body: UserSignatureSubmitRequest,
        httpRequest: HttpServletRequest
    ): ResponseEntity<UserSignatureSessionDto> {
        if (body.signatureImageBase64.isBlank()) {
            return ResponseEntity.badRequest().body(UserSignatureSessionDto(status = "ERROR"))
        }
        userSignatureLinkService.submit(token, body.signatureImageBase64)
        return ResponseEntity.ok(UserSignatureSessionDto(status = "COMPLETED"))
    }
}

data class UserSignatureSessionDto(val status: String)
data class UserSignatureSubmitRequest(val signatureImageBase64: String)
