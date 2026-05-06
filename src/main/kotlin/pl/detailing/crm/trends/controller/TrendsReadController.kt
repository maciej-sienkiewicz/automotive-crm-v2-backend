package pl.detailing.crm.trends.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.trends.controller.dto.*
import pl.detailing.crm.trends.domain.KeywordStatus
import pl.detailing.crm.trends.repository.*
import pl.detailing.crm.trends.searchvolume.model.PolandLocations
import pl.detailing.crm.trends.searchvolume.model.VoivodeshipLocation
import pl.detailing.crm.trends.service.TrendAnalysisService

/**
 * Read-only REST API for the Trends module.
 *
 * All data is populated by [pl.detailing.crm.trends.scheduler.KeywordSyncScheduler].
 * No DataForSEO calls are made here — every request is served from the database.
 *
 * Raw search volumes are intentionally not exposed in responses:
 * Google buckets them into ~40 discrete steps, making exact numbers misleading.
 * Instead, responses expose normalized trend indices (0-100) and growth rates (%).
 */
@RestController
@RequestMapping("/api/trends")
class TrendsReadController(
    private val keywordRepo: TrackedKeywordRepository,
    private val metricsRepo: KeywordMetricsRepository,
    private val trendRepo: TrendDataRepository,
    private val syncRepo: SyncStatusRepository,
    private val trendAnalysis: TrendAnalysisService
) {

    /**
     * Lists tracked keywords with trend direction and short-term growth rate.
     *
     * growthRate = avg(last 30d trend index) vs avg(previous 30d trend index) [%]
     *
     * @param locationCode for metric context (competition, cpc) — default 2616 = Poland
     * @param sort         "growth" | "score" | "cpc" | "competition" | "keyword"
     * @param status       keyword status filter — default ACTIVE
     */
    @GetMapping("/keywords")
    fun listKeywords(
        @RequestParam(defaultValue = "2616")    locationCode: Int,
        @RequestParam(defaultValue = "growth")  sort: String,
        @RequestParam(defaultValue = "ACTIVE")  status: String
    ): ResponseEntity<KeywordsListResponse> {
        val keywordStatus = KeywordStatus.valueOf(status.uppercase())
        val keywords      = keywordRepo.findByStatus(keywordStatus)
        val metricsById   = metricsRepo.findByLocationCode(locationCode).associateBy { it.keywordId }

        val summaries = trendAnalysis.buildTrendSummaries(keywords, metricsById, locationCode)

        val sorted = when (sort.lowercase()) {
            "score"       -> summaries.sortedByDescending { it.relevanceScore ?: 0.0 }
            "cpc"         -> summaries.sortedByDescending { it.cpc ?: 0.0 }
            "competition" -> summaries.sortedByDescending { it.competitionIndex ?: 0 }
            "keyword"     -> summaries.sortedBy { it.keyword }
            else          -> summaries.sortedByDescending { it.growthRate ?: Double.MIN_VALUE }
        }

        return ResponseEntity.ok(
            KeywordsListResponse(
                locationCode  = locationCode,
                locationName  = PolandLocations.BY_CODE[locationCode]?.canonicalName ?: "Unknown",
                totalKeywords = sorted.size,
                keywords      = sorted
            )
        )
    }

    /**
     * Full trend detail for a single keyword:
     * unified normalized timeline (0-100) + MoM / QoQ / YoY growth rates.
     *
     * @param locationCode controls which location's monthly data feeds the timeline and growth rates
     */
    @GetMapping("/keywords/{keyword}/history")
    fun keywordHistory(
        @PathVariable                          keyword: String,
        @RequestParam(defaultValue = "2616")   locationCode: Int
    ): ResponseEntity<KeywordTrendDetailResponse> {
        val kw = keywordRepo.findByKeyword(keyword)
            ?: return ResponseEntity.notFound().build()

        val metric = metricsRepo.findByKeywordId(kw.id).firstOrNull { it.locationCode == locationCode }
        val detail = trendAnalysis.buildTrendDetailWithMetrics(kw, locationCode, metric)

        return ResponseEntity.ok(detail)
    }

    /**
     * Dashboard overview: top 10 keywords by growth, sync statuses, location count.
     */
    @GetMapping("/summary")
    fun summary(): ResponseEntity<DashboardSummaryResponse> {
        val active     = keywordRepo.findByStatus(KeywordStatus.ACTIVE)
        val metricsById = metricsRepo.findByLocationCode(PolandLocations.COUNTRY.locationCode)
            .associateBy { it.keywordId }

        val top10 = trendAnalysis.buildTrendSummaries(active, metricsById, PolandLocations.COUNTRY.locationCode)
            .sortedByDescending { it.growthRate ?: Double.MIN_VALUE }
            .take(10)

        val syncStatuses = listOf("INITIAL_SEED", "VOLUME_REFRESH", "TREND_FILL", "KEYWORD_EXPANSION")
            .mapNotNull { syncRepo.find(it) }
            .map { SyncStatusDto(it.taskName, it.status.name, it.lastSuccessAt?.toString(), it.details) }

        return ResponseEntity.ok(
            DashboardSummaryResponse(
                totalTrackedKeywords = active.size,
                topKeywordsByVolume  = top10,
                syncStatuses         = syncStatuses,
                locations            = LocationsSummaryDto(
                    country          = PolandLocations.COUNTRY.canonicalName,
                    voivodeshipCount = PolandLocations.VOIVODESHIPS.size
                )
            )
        )
    }

    /**
     * Returns all available location codes for use as the `locationCode` parameter.
     * Call once on frontend init and cache the result.
     */
    @GetMapping("/locations")
    fun locations(): ResponseEntity<LocationsResponse> =
        ResponseEntity.ok(
            LocationsResponse(
                country      = PolandLocations.COUNTRY.toItem(),
                voivodeships = PolandLocations.VOIVODESHIPS.map { it.toItem() }
            )
        )

    /**
     * Compares a single keyword across all Polish voivodeships.
     * Returns trend direction and growth rate per region, sorted by growth.
     */
    @GetMapping("/voivodeships/{keyword}")
    fun voivodeshipComparison(
        @PathVariable keyword: String
    ): ResponseEntity<VoivodeshipComparisonResponse> {
        val kw = keywordRepo.findByKeyword(keyword)
            ?: return ResponseEntity.notFound().build()

        val allMetrics = metricsRepo.findByKeywordId(kw.id).associateBy { it.locationCode }

        val items = PolandLocations.ALL_CODES.map { code ->
            val loc    = PolandLocations.BY_CODE[code]
            val metric = allMetrics[code]

            // For regional growth rates use the detail computation per location
            val detail = trendAnalysis.buildTrendDetailWithMetrics(kw, code, metric)

            VoivodeshipMetricItem(
                locationCode     = code,
                locationName     = loc?.canonicalName ?: "Unknown",
                polishName       = loc?.polishName,
                geoLevel         = if (code == PolandLocations.COUNTRY.locationCode) "country" else "voivodeship",
                growthRate       = detail.growthRates.monthOverMonth,
                latestTrendIndex = null,   // trend_data is country-only (Trends Explore limitation)
                trendDirection   = detail.trendDirection,
                competition      = metric?.competition,
                cpc              = metric?.cpc
            )
        }.sortedByDescending { it.growthRate ?: Double.MIN_VALUE }

        return ResponseEntity.ok(VoivodeshipComparisonResponse(keyword = keyword, locations = items))
    }

    private fun VoivodeshipLocation.toItem() =
        LocationItem(locationCode = locationCode, canonicalName = canonicalName, polishName = polishName)
}
