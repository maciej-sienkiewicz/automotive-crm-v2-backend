package pl.detailing.crm.communication.redirect

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.subscription.entitlement.capability.CapabilityKey
import pl.detailing.crm.subscription.entitlement.capability.RequiresCapability
import pl.detailing.crm.shared.pii.Pii
import java.time.Instant

data class CommunicationRedirectDto(
    val enabled: Boolean,
    @Pii val phone: String,
    @Pii val email: String,
    val updatedAt: Instant?
)

data class UpdateCommunicationRedirectRequest(
    val enabled: Boolean,
    val phone: String = "",
    val email: String = ""
)

/**
 * "Przekieruj każdą wiadomość mailową i SMS na moje dane" — the switch on the templates
 * screen. Same permission and module as editing the templates themselves.
 */
@RequiresPermission(Permission.COMMUNICATION_SEND)
@RequiresCapability(CapabilityKey.COMM_SEND_TRANSACTIONAL)
@RestController
@RequestMapping("/api/v1/communication/redirect")
class CommunicationRedirectController(
    private val service: CommunicationRedirectService
) {

    @GetMapping
    fun get(): ResponseEntity<CommunicationRedirectDto> =
        ResponseEntity.ok(service.settings(SecurityContextHelper.getCurrentStudioId()).toDto())

    @PutMapping
    fun update(@RequestBody request: UpdateCommunicationRedirectRequest): ResponseEntity<CommunicationRedirectDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        val settings = service.update(
            studioId = principal.studioId,
            enabled = request.enabled,
            phone = request.phone,
            email = request.email,
            updatedBy = principal.userId.value
        )
        return ResponseEntity.ok(settings.toDto())
    }

    private fun CommunicationRedirectSettings.toDto() = CommunicationRedirectDto(enabled, phone, email, updatedAt)
}
