package pl.detailing.crm.instagram.ads

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.subscription.entitlement.capability.CapabilityKey
import pl.detailing.crm.subscription.entitlement.capability.RequiresCapability
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Reklamy konkurencji z Biblioteki reklam Meta.
 *
 * Ten sam zakres uprawnień co reszta analityki konkurencji — to jedna zakładka
 * tego samego modułu, tylko o kanale płatnym zamiast organicznego.
 */
@RequiresPermission(Permission.MARKETING_MANAGE)
@RequiresCapability(CapabilityKey.INSTAGRAM_MONITOR)
@RestController
@RequestMapping("/api/v1/instagram/ads")
class MetaAdsController(
    private val readService: MetaAdsReadService
) {

    /** Kalendarz roku: kto, kiedy i jak długo się reklamował. */
    @GetMapping("/calendar")
    fun calendar(@RequestParam(required = false) year: Int?): ResponseEntity<AdCalendarResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val currentYear = LocalDate.now(ZoneOffset.UTC).year
        // Biblioteka trzyma rok wstecz, więc głębiej niż poprzedni rok nie ma czego szukać.
        val resolved = (year ?: currentYear).coerceIn(currentYear - 1, currentYear)
        return ResponseEntity.ok(readService.calendar(principal.studioId, resolved))
    }

    /** Szczegóły jednej kampanii: ustawienia odbiorców i rzeczywisty zasięg w Polsce. */
    @GetMapping("/{adId}")
    fun detail(@PathVariable adId: String): ResponseEntity<AdDetailDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        val detail = readService.detail(principal.studioId, adId)
        return detail?.let { ResponseEntity.ok(it) } ?: ResponseEntity.notFound().build()
    }

    /**
     * Wskazanie strony na Facebooku dla obserwowanego profilu — bez niej nie da
     * się sprawdzić, czy ten profil się reklamuje.
     */
    @PutMapping("/profiles/{profileId}/page")
    fun linkPage(
        @PathVariable profileId: UUID,
        @RequestBody request: LinkFacebookPageRequest
    ): ResponseEntity<Map<String, Boolean>> {
        val principal = SecurityContextHelper.getCurrentUser()
        val linked = readService.linkFacebookPage(principal.studioId, profileId, request)
        return if (linked) ResponseEntity.ok(mapOf("linked" to true))
        else ResponseEntity.badRequest().body(mapOf("linked" to false))
    }
}
