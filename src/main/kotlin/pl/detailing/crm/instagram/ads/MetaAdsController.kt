package pl.detailing.crm.instagram.ads

import pl.detailing.crm.rolepreview.SimulatedEffectChannel
import pl.detailing.crm.rolepreview.RolePreviewOutboundGuard
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.livemetrics.BusinessEventPublisher
import pl.detailing.crm.livemetrics.domain.BusinessEventType
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
    private val syncService: MetaAdsSyncService,
    private val businessEventPublisher: BusinessEventPublisher,
    private val rolePreviewGuard: RolePreviewOutboundGuard
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
            ?: return ResponseEntity.notFound().build()

        // Live metrics — liczymy otwarcia szczegółów kampanii. Świadomy wyjątek od reguły
        // „publish po save": to jedyne zdarzenie czysto odczytowe, więc punktem odniesienia
        // jest niepusty wynik odczytu, a nie zapis. 404 nie jest podglądem i nic nie liczy.
        businessEventPublisher.publish(
            tenantId = principal.studioId,
            type = BusinessEventType.INSTAGRAM_AD_DETAILS_VIEWED,
            attributes = mapOf("adId" to adId)
        )

        return ResponseEntity.ok(detail)
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
        rolePreviewGuard.requireOutsideSandbox(
            principal.studioId.value, SimulatedEffectChannel.INSTAGRAM, "powiązanie profilu ze stroną na Facebooku i pobranie reklam z Meta"
        )
        return when (val outcome = readService.linkFacebookPage(principal, profileId, request)) {
            is PageLinkOutcome.Rejected ->
                ResponseEntity.badRequest().body(mapOf("status" to "REJECTED"))

            is PageLinkOutcome.RequestSent ->
                ResponseEntity.accepted().body(mapOf("status" to "REQUESTED"))

            is PageLinkOutcome.Linked -> {
                val sync = syncService.syncProfile(profileId, outcome.pageId)
                // Nazwa strony wraca na ekran: to jedyne potwierdzenie, że numer należy do
                // tej firmy, o którą chodziło. Sam numer nic nie mówi, a pomyłka wciąga do
                // kalendarza reklamy obcego przedsiębiorstwa pod nazwą konkurenta.
                ResponseEntity.ok(
                    mapOf(
                        "status" to "LINKED",
                        "adsFound" to sync.adsSeen,
                        "pageName" to (sync.pageName ?: "")
                    )
                )
            }
        }
    }

    /**
     * Podpowiedzi stron do powiązania. Przyjmuje wszystko, co człowiek ma pod ręką:
     * wklejony adres strony (z numerem albo z aliasem), sam numer albo nazwę firmy.
     * Rozpoznanie postaci jest po naszej stronie — Facebook pokazuje tę samą stronę
     * raz jako `profile.php?id=…`, raz jako `facebook.com/CarArtDetailing`.
     */
    @GetMapping("/page-search")
    fun searchPages(@RequestParam q: String): ResponseEntity<Map<String, List<PageCandidateDto>>> {
        val principal = SecurityContextHelper.getCurrentUser()
        // Piaskownica podglądu roli nie odpytuje Meta - wyszukiwarka stron zwraca pustą listę.
        if (rolePreviewGuard.intercepts(principal.studioId.value, SimulatedEffectChannel.INSTAGRAM, null, "Wyszukanie stron na Facebooku: „$q\"")) {
            return ResponseEntity.ok(mapOf("candidates" to emptyList()))
        }
        return ResponseEntity.ok(mapOf("candidates" to readService.searchPages(q)))
    }

    /**
     * Odpięcie strony — prośba do administratora, nie zapis.
     *
     * Odpięcie kasowało migawki reklam wspólne dla wszystkich studiów obserwujących
     * profil, więc jedno kliknięcie zabierało historię także cudzym kalendarzom.
     */
    @DeleteMapping("/profiles/{profileId}/page")
    fun unlinkPage(@PathVariable profileId: UUID): ResponseEntity<Map<String, String>> {
        val principal = SecurityContextHelper.getCurrentUser()
        return when (readService.unlinkFacebookPage(principal, profileId)) {
            is PageLinkOutcome.RequestSent -> ResponseEntity.accepted().body(mapOf("status" to "REQUESTED"))
            else -> ResponseEntity.badRequest().body(mapOf("status" to "REJECTED"))
        }
    }
}
