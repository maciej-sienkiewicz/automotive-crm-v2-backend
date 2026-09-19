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
 * Jedno ustawienie na studio (`/settings`) i jedna tabela wyników (`/results`).
 * Nazwane śledzenia zniknęły razem z powodem, dla którego istniały: po przejściu
 * na wspólny katalog fraz różniły się już wyłącznie listą miejscowości.
 */
@RequiresPermission(Permission.MARKETING_MANAGE)
@RequiresCapability(CapabilityKey.INSTAGRAM_MONITOR)
@RestController
@RequestMapping("/api/v1/instagram/ads/discovery")
class AdDiscoveryController(
    private val readService: AdDiscoveryReadService,
    private val settingsService: AdAreaSettingsService,
    private val blockService: AdvertiserBlockService
) {

    /**
     * Katalog fraz w całości — zamknięta lista ustalona przez administratora aplikacji.
     *
     * Ekran ustawień dostaje katalog, a nie „co śledzisz": studio zaznacza odjęcia,
     * więc fraza dołożona przez administratora włącza się wszystkim sama.
     */
    @GetMapping("/phrases")
    fun phraseCatalog(): ResponseEntity<PhraseCatalogDto> {
        SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(
            PhraseCatalogDto(
                phrases = AdDiscoveryCatalog.ALL.map {
                    CatalogPhraseDto(
                        id = it.id,
                        text = it.text,
                        group = it.group.name,
                        groupLabel = it.group.label
                    )
                }
            )
        )
    }

    /** Ustawienia rejonu tego studia. Brak wiersza to nie błąd — oddajemy stan pusty. */
    @GetMapping("/settings")
    fun settings(): ResponseEntity<AreaSettingsDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(settingsService.get(principal.studioId))
    }

    /** Zapis rejonu i odznaczeń. Frazy trafią do biblioteki przy najbliższym odczycie wyników. */
    @PutMapping("/settings")
    fun saveSettings(@RequestBody request: SaveAreaSettingsRequest): ResponseEntity<AreaSettingsDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(settingsService.save(principal.studioId, principal.userId, request))
    }

    /**
     * Tabela reklamodawców dla rejonu tego studia, stronicowana.
     *
     * Może dociągnąć frazy, których nie ma w świeżym cache — dlatego pierwsze wejście
     * po dłuższej przerwie bywa wolniejsze, a kolejne (także innych studiów) idą
     * już z gotowego, wspólnego cache.
     */
    @GetMapping("/results")
    fun results(
        @RequestParam(required = false, defaultValue = "0") page: Int,
        @RequestParam(required = false) pageSize: Int?
    ): ResponseEntity<AreaResultsDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(readService.results(principal.studioId, page, pageSize))
    }

    /**
     * „Odznacz nowe": studio potwierdza, że przejrzało nowości w swoim rejonie.
     *
     * Odznaki gasną po tym od razu, także w pasku podpowiedzi na Tablicy — obie
     * liczby biorą się z tego samego ustawienia, więc nie da się ich rozjechać.
     * Studio bez wskazanego rejonu nie ma czego odznaczać i dostaje `null`.
     */
    @PostMapping("/novelty/ack")
    fun acknowledgeNovelty(): ResponseEntity<Map<String, String?>> {
        val principal = SecurityContextHelper.getCurrentUser()
        val acked = settingsService.acknowledgeNovelty(principal.studioId, principal.userId)
        return ResponseEntity.ok(mapOf("noveltyAckedThrough" to acked?.toString()))
    }

    // ── Wykluczeni reklamodawcy ──────────────────────────────────────────────
    //
    // Wyłącznie czarna lista TEGO studia. Wykluczeń globalnych (boty, hurtownie,
    // profile zza granicy) nie da się stąd ani obejrzeć, ani cofnąć — zakłada je
    // administrator aplikacji wprost w bazie i mają obowiązywać wszystkich.

    /** Reklamodawcy ukryci przez to studio — z możliwością przywrócenia. */
    @GetMapping("/blocks")
    fun listBlocks(): ResponseEntity<List<BlockedAdvertiserDto>> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(blockService.listOwn(principal.studioId))
    }

    /** Ukrycie reklamodawcy w tabelach tego studia. */
    @PostMapping("/blocks")
    fun block(@RequestBody request: BlockAdvertiserRequest): ResponseEntity<BlockedAdvertiserDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        val blocked = blockService.block(principal.studioId, principal.userId, request)
        return ResponseEntity.status(HttpStatus.CREATED).body(blocked)
    }

    /** Przywrócenie reklamodawcy. Wykluczenia globalnego to nie ruszy. */
    @DeleteMapping("/blocks/{pageId}")
    fun unblock(@PathVariable pageId: String): ResponseEntity<Map<String, Boolean>> {
        val principal = SecurityContextHelper.getCurrentUser()
        return if (blockService.unblock(principal.studioId, pageId)) {
            ResponseEntity.ok(mapOf("restored" to true))
        } else {
            ResponseEntity.notFound().build()
        }
    }
}
