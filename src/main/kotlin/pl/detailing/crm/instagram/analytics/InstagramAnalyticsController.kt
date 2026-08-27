package pl.detailing.crm.instagram.analytics

import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import java.util.*
import pl.detailing.crm.subscription.entitlement.capability.CapabilityKey
import pl.detailing.crm.subscription.entitlement.capability.RequiresCapability

/**
 * API v2 analityki konkurencji. Kontrakt: każda metryka jako [MetricTriple]
 * (wartość + delta + benchmark) – frontend nie pokazuje liczb bez kontekstu.
 */
@RequiresPermission(Permission.MARKETING_MANAGE)
@RequiresCapability(CapabilityKey.INSTAGRAM_MONITOR)
@RestController
@RequestMapping("/api/v1/instagram")
class InstagramAnalyticsController(
    private val readService: InstagramAnalyticsReadService,
    private val suggestionService: SuggestionService,
    private val weeklyDigestService: WeeklyDigestService,
    private val competitorPulseService: CompetitorPulseService
) {

    @GetMapping("/overview")
    fun overview(
        @RequestParam(defaultValue = "12") weeks: Int
    ): ResponseEntity<OverviewResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        ResponseEntity.ok(readService.overview(principal.studioId, weeks.coerceIn(4, 52)))
    }

    @GetMapping("/benchmark")
    fun benchmark(
        @RequestParam(defaultValue = "12") weeks: Int
    ): ResponseEntity<BenchmarkResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        ResponseEntity.ok(readService.benchmark(principal.studioId, weeks.coerceIn(4, 52)))
    }

    /**
     * "Puls" — co wydarzyło się u obserwowanych profili w ostatnich dniach.
     *
     * Same fakty zestawione z normą profilu z pół roku; bez wniosków o wzorcach, bo przy
     * ~1,5 posta tygodniowo na profil krótkie okno nie ma na nie pokrycia. Liczone
     * wyłącznie w kodzie, więc bez limitów i bez cache — zapytanie jest tanie.
     */
    @GetMapping("/pulse")
    fun pulse(
        @RequestParam(defaultValue = "1") weeks: Int
    ): ResponseEntity<CompetitorPulseDto> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        ResponseEntity.ok(competitorPulseService.pulse(principal.studioId, weeks.coerceIn(1, 4)))
    }

    /** Wyjaśnienie tygodnia dla kliknięcia w słupek przyrostu obserwujących. */
    @GetMapping("/benchmark/week-detail")
    fun weekDetail(
        @RequestParam profileId: String,
        @RequestParam weekStart: String
    ): ResponseEntity<WeekDetailResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        ResponseEntity.ok(readService.weekDetail(principal.studioId, profileId, weekStart))
    }

    @GetMapping("/content")
    fun content(
        @RequestParam(defaultValue = "12") weeks: Int,
        @RequestParam(defaultValue = "engagement") sort: String,
        @RequestParam(required = false) topic: String?,
        @RequestParam(required = false) format: String?,
        @RequestParam(required = false) profileId: String?,
        @RequestParam(defaultValue = "false") promoOnly: Boolean,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "24") pageSize: Int
    ): ResponseEntity<ContentResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        ResponseEntity.ok(
            readService.content(
                studioId = principal.studioId,
                weeks = weeks.coerceIn(4, 52),
                sort = sort,
                topic = topic?.takeIf { it.isNotBlank() },
                format = format?.takeIf { it.isNotBlank() },
                profileId = profileId?.takeIf { it.isNotBlank() },
                promoOnly = promoOnly,
                page = page.coerceAtLeast(0),
                pageSize = pageSize.coerceIn(1, 60)
            )
        )
    }

    @GetMapping("/content/heatmap")
    fun heatmap(
        @RequestParam(defaultValue = "12") weeks: Int
    ): ResponseEntity<HeatmapResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        ResponseEntity.ok(readService.heatmap(principal.studioId, weeks.coerceIn(4, 52)))
    }

    @GetMapping("/hashtags")
    fun hashtags(
        @RequestParam(defaultValue = "12") weeks: Int
    ): ResponseEntity<HashtagsResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        ResponseEntity.ok(readService.hashtags(principal.studioId, weeks.coerceIn(4, 52)))
    }

    /** Sugestie "Zaobserwuj podobne profile" z cache related-profiles. */
    @GetMapping("/suggestions")
    fun suggestions(): ResponseEntity<Map<String, List<ProfileSuggestionDto>>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        ResponseEntity.ok(mapOf("suggestions" to suggestionService.suggestionsForStudio(principal.studioId)))
    }

    /**
     * Tydzień na Instagramie: jeden wiersz na obserwowany profil — co zrobił,
     * co z tego wyszło i co on sam faktycznie zrealizował (z opisów postów).
     *
     * Zastąpił parę „przegląd + raport tygodnia", które pokazywały te same
     * insighty, te same metryki i tę samą pozycję w dwóch układach graficznych.
     * Generowany raz na tydzień i odświeżany po synchronizacji, więc odczyt jest
     * tani mimo jednego wywołania modelu w środku.
     */
    @GetMapping("/digest")
    fun digest(): ResponseEntity<Map<String, WeeklyDigestDto?>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        ResponseEntity.ok(mapOf("digest" to weeklyDigestService.digest(principal.studioId)))
    }
}
