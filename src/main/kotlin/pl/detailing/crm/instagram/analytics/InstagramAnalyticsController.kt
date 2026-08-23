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
    private val reportService: ReportService,
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

    @GetMapping("/insights")
    fun insights(
        @RequestParam(required = false) status: String?,
        @RequestParam(defaultValue = "20") limit: Int
    ): ResponseEntity<InsightsListResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        ResponseEntity.ok(readService.listInsights(principal.studioId, status?.takeIf { it.isNotBlank() }, limit))
    }

    /** Sugestie "Zaobserwuj podobne profile" z cache related-profiles. */
    @GetMapping("/suggestions")
    fun suggestions(): ResponseEntity<Map<String, List<ProfileSuggestionDto>>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        ResponseEntity.ok(mapOf("suggestions" to suggestionService.suggestionsForStudio(principal.studioId)))
    }

    /** Raport za ostatni zamknięty tydzień (generowany leniwie przy pierwszym odczycie). */
    @GetMapping("/reports/latest")
    fun latestReport(): ResponseEntity<Map<String, ReportDto?>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        ResponseEntity.ok(mapOf("report" to reportService.latest(principal.studioId)))
    }

    @GetMapping("/reports")
    fun listReports(
        @RequestParam(defaultValue = "12") limit: Int
    ): ResponseEntity<Map<String, List<ReportBriefDto>>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        ResponseEntity.ok(mapOf("reports" to reportService.list(principal.studioId, limit)))
    }

    @GetMapping("/reports/{id}")
    fun reportById(@PathVariable id: String): ResponseEntity<Map<String, ReportDto?>> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        ResponseEntity.ok(mapOf("report" to reportService.byId(principal.studioId, UUID.fromString(id))))
    }

    @PostMapping("/insights/{id}/dismiss")
    fun dismissInsight(@PathVariable id: String): ResponseEntity<Void> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        readService.dismissInsight(principal.studioId, UUID.fromString(id))
        ResponseEntity.noContent().build()
    }
}
