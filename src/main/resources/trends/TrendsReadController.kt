package com.example.demo.trends.controller

import com.example.demo.trends.repository.*
import com.example.demo.trends.searchvolume.model.PolandLocations
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

/**
 * Read-only REST API for keyword trends data.
 *
 * All data is populated autonomously by the scheduler.
 * This controller only reads from the database — no API calls to DataForSEO.
 */
@RestController
@RequestMapping("/api/trends")
class TrendsReadController(
    private val keywordRepo: TrackedKeywordRepository,
    private val metricsRepo: KeywordMetricsRepository,
    private val monthlyRepo: MonthlySearchRepository,
    private val trendRepo: TrendDataRepository,
    private val syncRepo: SyncStatusRepository
) {

    // ═══════════════════════════════════════════════════════════════
    // GET /api/trends/keywords — list all keywords with metrics
    // ═══════════════════════════════════════════════════════════════

    /**
     * Lists all tracked keywords with their latest metrics.
     *
     * Query params:
     *   - locationCode: filter by location (default: 2616 = Poland)
     *   - sort: "volume" (default), "cpc", "competition", "keyword"
     *   - status: filter by keyword status (default: ACTIVE)
     */
    @GetMapping("/keywords")
    fun listKeywords(
        @RequestParam(defaultValue = "2616") locationCode: Int,
        @RequestParam(defaultValue = "volume") sort: String,
        @RequestParam(defaultValue = "ACTIVE") status: String
    ): ResponseEntity<KeywordsListResponse> {
        val keywords = keywordRepo.findByStatus(status)
        val metrics = metricsRepo.findByLocationCode(locationCode)
        val metricsMap = metrics.associateBy { it.keywordId }

        val items = keywords.map { kw ->
            val metric = metricsMap[kw.id]
            KeywordListItem(
                keyword = kw.keyword,
                searchVolume = metric?.searchVolume,
                cpc = metric?.cpc,
                competition = metric?.competition,
                competitionIndex = metric?.competitionIndex,
                lastFetchedAt = kw.lastFetchedAt?.toString()
            )
        }

        val sorted = when (sort) {
            "cpc" -> items.sortedByDescending { it.cpc ?: 0.0 }
            "competition" -> items.sortedByDescending { it.competitionIndex ?: 0 }
            "keyword" -> items.sortedBy { it.keyword }
            else -> items.sortedByDescending { it.searchVolume ?: 0 }
        }

        val locationName = PolandLocations.BY_CODE[locationCode]?.canonicalName ?: "Unknown"

        return ResponseEntity.ok(KeywordsListResponse(
            locationCode = locationCode,
            locationName = locationName,
            totalKeywords = sorted.size,
            keywords = sorted
        ))
    }

    // ═══════════════════════════════════════════════════════════════
    // GET /api/trends/keywords/{keyword}/history — timeline for one keyword
    // ═══════════════════════════════════════════════════════════════

    /**
     * Returns full history for a keyword: monthly volumes + daily trend.
     *
     * Query params:
     *   - locationCode: for monthly data (default: 2616 = Poland)
     *   - from / to: date range for trend data (default: last 12 months)
     */
    @GetMapping("/keywords/{keyword}/history")
    fun keywordHistory(
        @PathVariable keyword: String,
        @RequestParam(defaultValue = "2616") locationCode: Int,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?
    ): ResponseEntity<KeywordHistoryResponse> {
        val kw = keywordRepo.findByKeyword(keyword)
            ?: return ResponseEntity.notFound().build()

        val monthly = monthlyRepo.findByKeywordAndLocation(kw.id, locationCode)
        val fromDate = from?.let { LocalDate.parse(it) } ?: LocalDate.now().minusMonths(12)
        val toDate = to?.let { LocalDate.parse(it) } ?: LocalDate.now()
        val trends = trendRepo.findByKeyword(kw.id, fromDate, toDate)

        val metric = metricsRepo.findByKeywordId(kw.id).firstOrNull { it.locationCode == locationCode }

        return ResponseEntity.ok(KeywordHistoryResponse(
            keyword = kw.keyword,
            locationCode = locationCode,
            locationName = PolandLocations.BY_CODE[locationCode]?.canonicalName ?: "Unknown",
            currentMetrics = metric?.let {
                CurrentMetrics(
                    searchVolume = it.searchVolume,
                    cpc = it.cpc,
                    competition = it.competition,
                    competitionIndex = it.competitionIndex
                )
            },
            monthlySearches = monthly.map { MonthlyPoint(it.year, it.month, it.searchVolume) },
            dailyTrend = trends.map { DailyTrendPoint(it.date.toString(), it.trendIndex) }
        ))
    }

    // ═══════════════════════════════════════════════════════════════
    // GET /api/trends/summary — dashboard overview
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/summary")
    fun summary(): ResponseEntity<DashboardSummary> {
        val active = keywordRepo.findByStatus("ACTIVE")
        val countryMetrics = metricsRepo.findByLocationCode(PolandLocations.COUNTRY.locationCode)
        val metricsMap = countryMetrics.associateBy { it.keywordId }

        val topByVolume = active
            .mapNotNull { kw -> metricsMap[kw.id]?.let { kw to it } }
            .sortedByDescending { it.second.searchVolume ?: 0 }
            .take(10)
            .map { (kw, m) -> KeywordListItem(kw.keyword, m.searchVolume, m.cpc, m.competition, m.competitionIndex, null) }

        val syncStatuses = listOf("INITIAL_SEED", "VOLUME_REFRESH", "TREND_FILL")
            .mapNotNull { syncRepo.get(it) }
            .map { SyncInfo(it.taskName, it.status, it.lastSuccessAt?.toString(), it.details) }

        return ResponseEntity.ok(DashboardSummary(
            totalTrackedKeywords = active.size,
            topKeywordsByVolume = topByVolume,
            syncStatuses = syncStatuses,
            locations = LocationsSummary(
                country = PolandLocations.COUNTRY.canonicalName,
                voivodeshipCount = PolandLocations.VOIVODESHIPS.size
            )
        ))
    }

    // ═══════════════════════════════════════════════════════════════
    // GET /api/trends/voivodeships/{keyword} — compare across regions
    // ═══════════════════════════════════════════════════════════════

    @GetMapping("/voivodeships/{keyword}")
    fun voivodeshipComparison(@PathVariable keyword: String): ResponseEntity<VoivodeshipComparisonResponse> {
        val kw = keywordRepo.findByKeyword(keyword)
            ?: return ResponseEntity.notFound().build()

        val allMetrics = metricsRepo.findByKeywordId(kw.id)

        val items = allMetrics.map { m ->
            val loc = PolandLocations.BY_CODE[m.locationCode]
            VoivodeshipMetricItem(
                locationCode = m.locationCode,
                locationName = loc?.canonicalName ?: "Unknown",
                polishName = loc?.polishName,
                geoLevel = if (m.locationCode == PolandLocations.COUNTRY.locationCode) "country" else "voivodeship",
                searchVolume = m.searchVolume,
                cpc = m.cpc,
                competition = m.competition
            )
        }.sortedByDescending { it.searchVolume ?: 0 }

        return ResponseEntity.ok(VoivodeshipComparisonResponse(
            keyword = keyword,
            locations = items
        ))
    }
}

// ═══════════════════════════════════════════════════════════════════════
// DTOs
// ═══════════════════════════════════════════════════════════════════════

data class KeywordsListResponse(
    val locationCode: Int,
    val locationName: String,
    val totalKeywords: Int,
    val keywords: List<KeywordListItem>
)

data class KeywordListItem(
    val keyword: String,
    val searchVolume: Int?,
    val cpc: Double?,
    val competition: String?,
    val competitionIndex: Int?,
    val lastFetchedAt: String?
)

data class KeywordHistoryResponse(
    val keyword: String,
    val locationCode: Int,
    val locationName: String,
    val currentMetrics: CurrentMetrics?,
    val monthlySearches: List<MonthlyPoint>,
    val dailyTrend: List<DailyTrendPoint>
)

data class CurrentMetrics(
    val searchVolume: Int?,
    val cpc: Double?,
    val competition: String?,
    val competitionIndex: Int?
)

data class MonthlyPoint(val year: Int, val month: Int, val searchVolume: Int?)
data class DailyTrendPoint(val date: String, val trendIndex: Int?)

data class DashboardSummary(
    val totalTrackedKeywords: Int,
    val topKeywordsByVolume: List<KeywordListItem>,
    val syncStatuses: List<SyncInfo>,
    val locations: LocationsSummary
)

data class SyncInfo(
    val taskName: String,
    val status: String,
    val lastSuccessAt: String?,
    val details: String?
)

data class LocationsSummary(val country: String, val voivodeshipCount: Int)

data class VoivodeshipComparisonResponse(
    val keyword: String,
    val locations: List<VoivodeshipMetricItem>
)

data class VoivodeshipMetricItem(
    val locationCode: Int,
    val locationName: String,
    val polishName: String?,
    val geoLevel: String,
    val searchVolume: Int?,
    val cpc: Double?,
    val competition: String?
)

