package pl.detailing.crm.user

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.user.signature.UserSignatureService

@RestController
@RequestMapping("/api/v1/profile")
class ProfileController(
    private val userSignatureService: UserSignatureService
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
}

data class SaveSignatureRequest(val signatureImageBase64: String)
data class SignatureStatusResponse(val hasSignature: Boolean, val url: String?)
