package pl.detailing.crm.instagram.ads.discovery

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.subscription.entitlement.capability.CapabilityKey
import pl.detailing.crm.subscription.entitlement.capability.RequiresCapability
import java.util.UUID

/**
 * Odkrywanie obszaru: „Kto jeszcze reklamuje się na frazy X, Y w rejonie
 * Poznań, Skórzewo, Suchy Las".
 *
 * Ten sam zakres uprawnień i ta sama funkcja abonamentowa co reszta analityki
 * konkurencji — to zakładka tego samego modułu, tylko odkrywa reklamodawców po
 * treści i terenie zamiast śledzić z góry znane profile.
 *
 * Dwie ścieżki:
 *   - `/preview` — pokaż od ręki, bez zapisu (do decyzji „czy w ogóle śledzić");
 *   - `/trackings` — trwałe śledzenie, którego frazy odświeżają się cyklicznie.
 * Obie oddają ten sam kształt wyników [AreaResultsDto].
 */
@RequiresPermission(Permission.MARKETING_MANAGE)
@RequiresCapability(CapabilityKey.INSTAGRAM_MONITOR)
@RestController
@RequestMapping("/api/v1/instagram/ads/discovery")
class AdDiscoveryController(
    private val readService: AdDiscoveryReadService,
    private val trackingService: AdLocationTrackingService
) {

    /** Podgląd na żywo: frazy + rejon → tabela firm. Może dociągnąć nowe frazy do wspólnego cache. */
    @PostMapping("/preview")
    fun preview(@RequestBody request: AreaPreviewRequest): ResponseEntity<AreaResultsDto> {
        SecurityContextHelper.getCurrentUser()
        val results = readService.results(
            phrases = request.phrases,
            locations = request.locations,
            mode = request.matchMode ?: AreaMatchMode.INCLUDE_BROADER
        )
        return ResponseEntity.ok(results)
    }

    /** Lista śledzeń obszaru tego studia. */
    @GetMapping("/trackings")
    fun listTrackings(): ResponseEntity<List<LocationTrackingDto>> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(trackingService.list(principal.studioId))
    }

    /** Zapis nowego śledzenia. Frazy trafią do biblioteki przy pierwszym odczycie wyników. */
    @PostMapping("/trackings")
    fun createTracking(@RequestBody request: SaveLocationTrackingRequest): ResponseEntity<LocationTrackingDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        val created = trackingService.create(principal.studioId, principal.userId, request)
        return ResponseEntity.status(HttpStatus.CREATED).body(created)
    }

    /** Edycja śledzenia — także wstrzymanie/wznowienie przez pole `active`. */
    @PutMapping("/trackings/{id}")
    fun updateTracking(
        @PathVariable id: UUID,
        @RequestBody request: SaveLocationTrackingRequest
    ): ResponseEntity<LocationTrackingDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        return trackingService.update(principal.studioId, id, request)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()
    }

    @DeleteMapping("/trackings/{id}")
    fun deleteTracking(@PathVariable id: UUID): ResponseEntity<Map<String, Boolean>> {
        val principal = SecurityContextHelper.getCurrentUser()
        return if (trackingService.delete(principal.studioId, id)) {
            ResponseEntity.ok(mapOf("deleted" to true))
        } else {
            ResponseEntity.notFound().build()
        }
    }

    /** Tabela wyników zapisanego śledzenia — z bieżącego, wspólnego cache. */
    @GetMapping("/trackings/{id}/results")
    fun trackingResults(@PathVariable id: UUID): ResponseEntity<AreaResultsDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        val tracking = trackingService.get(principal.studioId, id) ?: return ResponseEntity.notFound().build()
        val results = readService.results(tracking.phrases, tracking.locations, tracking.matchMode)
        return ResponseEntity.ok(results)
    }
}
