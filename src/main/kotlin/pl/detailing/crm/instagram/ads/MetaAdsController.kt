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
    private val readService: MetaAdsReadService,
    private val syncService: MetaAdsSyncService
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
     *
     * Reklamy pobieramy OD RAZU, w tym samym żądaniu. Powiązanie samo w sobie jest
     * ruchem bez skutku: właściciel wpisywał identyfikator, wiersz pojawiał się
     * w kalendarzu pusty i tak zostawało do nocnego przebiegu. Skoro człowiek
     * właśnie powiedział nam, gdzie patrzeć, patrzymy od razu — jedno wywołanie
     * do Meta, a odpowiedź mówi, ile reklam znaleźliśmy.
     *
     * Pobranie stoi POZA transakcją zapisu (osobny serwis): transakcja rozpięta
     * wokół wywołania HTTP trzymałaby połączenie z bazą przez cały czas odpowiedzi
     * obcego serwera.
     */
    @PutMapping("/profiles/{profileId}/page")
    fun linkPage(
        @PathVariable profileId: UUID,
        @RequestBody request: LinkFacebookPageRequest
    ): ResponseEntity<Map<String, Any>> {
        val principal = SecurityContextHelper.getCurrentUser()
        val pageId = readService.linkFacebookPage(principal.studioId, profileId, request)
            ?: return ResponseEntity.badRequest().body(mapOf("linked" to false))

        val sync = syncService.syncProfile(profileId, pageId)
        // Nazwa strony wraca na ekran: to jedyne potwierdzenie, że numer należy do
        // tej firmy, o którą chodziło. Sam numer nic nie mówi, a pomyłka wciąga do
        // kalendarza reklamy obcego przedsiębiorstwa pod nazwą konkurenta.
        return ResponseEntity.ok(
            mapOf("linked" to true, "adsFound" to sync.adsSeen, "pageName" to (sync.pageName ?: ""))
        )
    }

    /**
     * Podpowiedzi stron do powiązania. Przyjmuje wszystko, co człowiek ma pod ręką:
     * wklejony adres strony (z numerem albo z aliasem), sam numer albo nazwę firmy.
     * Rozpoznanie postaci jest po naszej stronie — Facebook pokazuje tę samą stronę
     * raz jako `profile.php?id=…`, raz jako `facebook.com/CarArtDetailing`.
     */
    @GetMapping("/page-search")
    fun searchPages(@RequestParam q: String): ResponseEntity<Map<String, List<PageCandidateDto>>> {
        SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(mapOf("candidates" to readService.searchPages(q)))
    }

    /** Odpięcie strony — razem z migawkami reklam, bo opisują już cudzą firmę. */
    @DeleteMapping("/profiles/{profileId}/page")
    fun unlinkPage(@PathVariable profileId: UUID): ResponseEntity<Map<String, Boolean>> {
        val principal = SecurityContextHelper.getCurrentUser()
        val unlinked = readService.unlinkFacebookPage(principal.studioId, profileId)
        return if (unlinked) ResponseEntity.ok(mapOf("unlinked" to true))
        else ResponseEntity.badRequest().body(mapOf("unlinked" to false))
    }
}
