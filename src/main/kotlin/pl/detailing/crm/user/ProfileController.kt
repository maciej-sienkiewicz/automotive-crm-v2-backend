package pl.detailing.crm.user

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.user.signature.UserSignatureLinkService
import pl.detailing.crm.user.signature.UserSignatureService
import java.time.Instant

@RestController
@RequestMapping("/api/v1/profile")
class ProfileController(
    private val userSignatureService: UserSignatureService,
    private val userSignatureLinkService: UserSignatureLinkService
) {

    @GetMapping("/signature")
    fun getSignatureStatus(): ResponseEntity<SignatureStatusResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val status = withContext(Dispatchers.IO) {
            userSignatureService.getStatus(principal.studioId, principal.userId)
        }
        ResponseEntity.ok(SignatureStatusResponse(hasSignature = status.hasSignature, url = status.url))
    }

    @PutMapping("/signature")
    fun saveSignature(@RequestBody request: SaveSignatureRequest): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        withContext(Dispatchers.IO) {
            userSignatureService.save(principal.studioId, principal.userId, request.signatureImageBase64)
        }
        ResponseEntity.noContent().build()
    }

    @DeleteMapping("/signature")
    fun deleteSignature(): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        withContext(Dispatchers.IO) {
            userSignatureService.delete(principal.studioId, principal.userId)
        }
        ResponseEntity.noContent().build()
    }

    /** Send a signing link by SMS to the provided phone number. */
    @PostMapping("/signature/send-link")
    fun sendSignatureLink(@RequestBody request: SendLinkRequest): ResponseEntity<SendLinkResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val result = withContext(Dispatchers.IO) {
            userSignatureLinkService.sendLink(principal.studioId, principal.userId, request.phoneNumber)
        }
        ResponseEntity.ok(SendLinkResponse(expiresAt = result.expiresAt))
    }
}

data class SaveSignatureRequest(val signatureImageBase64: String)
data class SendLinkRequest(val phoneNumber: String)
data class SignatureStatusResponse(val hasSignature: Boolean, val url: String?)
data class SendLinkResponse(val expiresAt: Instant)
