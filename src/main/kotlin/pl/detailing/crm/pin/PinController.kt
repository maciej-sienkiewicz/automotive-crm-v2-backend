package pl.detailing.crm.pin

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.context.SecurityContextRepository
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.auth.UserData
import pl.detailing.crm.auth.UnifiedAuthResponse
import pl.detailing.crm.role.permission.PermissionCheckService
import pl.detailing.crm.shared.ForbiddenException
import pl.detailing.crm.subscription.SubscriptionService
import pl.detailing.crm.user.infrastructure.UserRepository
import pl.detailing.crm.voice.MobileTokenService
import java.util.UUID

@RestController
@RequestMapping("/api/v1/pin")
class PinController(
    private val setPinHandler: SetPinHandler,
    private val switchUserViaPinHandler: SwitchUserViaPinHandler,
    private val userRepository: UserRepository,
    private val subscriptionService: SubscriptionService,
    private val securityContextRepository: SecurityContextRepository,
    private val mobileTokenService: MobileTokenService,
    private val permissionCheckService: PermissionCheckService
) {

    /** Current user's PIN status. */
    @GetMapping("/my-status")
    fun myPinStatus(): ResponseEntity<PinStatusResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val user = userRepository.findById(principal.userId.value).orElseThrow()
        ResponseEntity.ok(PinStatusResponse(
            hasPinConfigured = user.pinHash != null,
            pinLocked = user.pinLocked
        ))
    }

    /** Set or reset PIN for the currently authenticated user (requires current password). */
    @PostMapping("/set")
    fun setPin(@RequestBody request: SetPinRequest): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        setPinHandler.handle(principal.userId.value, principal.studioId.value, request.currentPassword, request.pin)
        ResponseEntity.noContent().build()
    }

    /**
     * Switch the current session to a different user in the same studio using PIN.
     * Returns the new user's auth data on success.
     */
    @PostMapping("/switch")
    fun switchUser(
        @RequestBody request: SwitchUserRequest,
        httpRequest: HttpServletRequest,
        httpResponse: HttpServletResponse
    ): ResponseEntity<UnifiedAuthResponse> = runBlocking {
        val currentPrincipal = SecurityContextHelper.getCurrentUser()
        val targetUserId = UUID.fromString(request.userId)

        val (response, newPrincipal) = switchUserViaPinHandler.handle(
            targetUserId = targetUserId,
            studioId = currentPrincipal.studioId.value,
            pin = request.pin
        )

        val context = SecurityContextHolder.createEmptyContext()
        context.authentication = newPrincipal
        SecurityContextHolder.setContext(context)
        securityContextRepository.saveContext(context, httpRequest, httpResponse)

        ResponseEntity.ok(response)
    }

    /** List all active users in the same studio (for the profile switcher UI). */
    @GetMapping("/studio-profiles")
    fun studioProfiles(): ResponseEntity<List<StudioProfileResponse>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val users = userRepository.findActiveByStudioId(principal.studioId.value)
        val profiles = users.map { u ->
            StudioProfileResponse(
                userId = u.id.toString(),
                firstName = u.firstName,
                lastName = u.lastName,
                isOwner = u.isOwner,
                hasPinConfigured = u.pinHash != null,
                pinLocked = u.pinLocked
            )
        }
        ResponseEntity.ok(profiles)
    }

    /** Reset PIN lock for a user (owner only — e.g. after 3 failed attempts). */
    @PostMapping("/reset-lock/{userId}")
    fun resetPinLock(@PathVariable userId: String): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        if (!principal.isOwner) throw ForbiddenException("Tylko właściciel może odblokować PIN")

        val targetId = UUID.fromString(userId)
        val user = userRepository.findByIdAndStudioId(targetId, principal.studioId.value)
            ?: return@runBlocking ResponseEntity.notFound().build<Void>()

        user.pinLocked = false
        user.pinFailedAttempts = 0
        userRepository.save(user)
        ResponseEntity.noContent().build()
    }
}

// ─── Request / Response DTOs ──────────────────────────────────────────────────

data class SetPinRequest(
    val currentPassword: String,
    val pin: String
)

data class SwitchUserRequest(
    val userId: String,
    val pin: String
)

data class PinStatusResponse(
    val hasPinConfigured: Boolean,
    val pinLocked: Boolean
)

data class StudioProfileResponse(
    val userId: String,
    val firstName: String,
    val lastName: String,
    val isOwner: Boolean,
    val hasPinConfigured: Boolean,
    val pinLocked: Boolean
)
