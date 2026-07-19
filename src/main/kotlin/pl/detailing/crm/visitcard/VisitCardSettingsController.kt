package pl.detailing.crm.visitcard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.studio.settings.StudioSettingsEntity
import pl.detailing.crm.studio.settings.StudioSettingsRepository
import pl.detailing.crm.subscription.entitlement.EntitlementService
import pl.detailing.crm.subscription.entitlement.FeatureKey
import pl.detailing.crm.subscription.entitlement.RequiresFeature
import java.time.Instant

/**
 * Studio-level configuration of the customer Visit Card:
 *
 *  - [enabled]        — "Czy korzystać z Karty Wizyty?" Master switch; when off,
 *                       the send checkboxes disappear from booking/check-in and
 *                       the send handlers refuse to deliver the link.
 *  - [sendByDefault]  — "Czy domyślnie wysyłać Kartę Wizyty?" Drives the default
 *                       state of the "Wyślij Kartę Wizyty do klienta" checkbox.
 *
 * The Visit Card requires the purchased SMS module (SMS_EMAIL): changing the
 * configuration is feature-gated, and [smsModuleActive] lets the frontend
 * render the proper upsell state. Reading is open to any employee — booking
 * and check-in views need the defaults.
 */
@RestController
@RequestMapping("/api/v1/settings/visit-card")
class VisitCardSettingsController(
    private val studioSettingsRepository: StudioSettingsRepository,
    private val entitlementService: EntitlementService
) {

    @GetMapping
    fun getSettings(): ResponseEntity<VisitCardSettingsResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val settings = withContext(Dispatchers.IO) {
            studioSettingsRepository.findById(principal.studioId.value).orElse(null)
        }
        ResponseEntity.ok(
            VisitCardSettingsResponse(
                enabled = settings?.visitCardEnabled ?: true,
                sendByDefault = settings?.visitCardSendByDefault ?: false,
                smsModuleActive = entitlementService.hasFeature(principal.studioId, FeatureKey.SMS_EMAIL)
            )
        )
    }

    @PutMapping
    @RequiresFeature(FeatureKey.SMS_EMAIL)
    fun updateSettings(
        @RequestBody request: UpdateVisitCardSettingsRequest
    ): ResponseEntity<VisitCardSettingsResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        val studioId = principal.studioId.value

        val settings = withContext(Dispatchers.IO) {
            studioSettingsRepository.findById(studioId).orElse(null)
                ?: StudioSettingsEntity(studioId = studioId)
        }

        request.enabled?.let { settings.visitCardEnabled = it }
        request.sendByDefault?.let { settings.visitCardSendByDefault = it }
        // A disabled card cannot be "sent by default" — keep the pair coherent
        if (!settings.visitCardEnabled) {
            settings.visitCardSendByDefault = false
        }
        settings.updatedAt = Instant.now()

        withContext(Dispatchers.IO) { studioSettingsRepository.save(settings) }

        ResponseEntity.ok(
            VisitCardSettingsResponse(
                enabled = settings.visitCardEnabled,
                sendByDefault = settings.visitCardSendByDefault,
                smsModuleActive = true
            )
        )
    }
}

data class VisitCardSettingsResponse(
    val enabled: Boolean,
    val sendByDefault: Boolean,
    /** Whether the studio's subscription currently includes the SMS module. */
    val smsModuleActive: Boolean
)

data class UpdateVisitCardSettingsRequest(
    val enabled: Boolean? = null,
    val sendByDefault: Boolean? = null
)
