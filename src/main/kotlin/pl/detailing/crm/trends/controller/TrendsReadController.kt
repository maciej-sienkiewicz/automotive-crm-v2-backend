package pl.detailing.crm.trends.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.trends.controller.dto.*
import pl.detailing.crm.trends.domain.KeywordStatus
import pl.detailing.crm.trends.repository.*
import pl.detailing.crm.trends.searchvolume.model.PolandLocations
import java.time.LocalDate

/**
 * Read-only REST API for the Trends module.
 *
 * All data is populated by [pl.detailing.crm.trends.scheduler.KeywordSyncScheduler].
 * No DataForSEO calls are made here — every request is served from the database.
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

    /**
     * Lists all tracked keywords with their latest metrics for a given location.
     *
     * @param locationCode Google Ads location code (default 2616 = Poland)
     * @param sort         "volume" | "cpc" | "competition" | "keyword"
     * @param status       keyword status filter (default ACTIVE)
     */
    @GetMapping("/keywords")
    fun listKeywords(
        @RequestParam(defaultValue = "2616")   locationCode: Int,
        @RequestParam(defaultValue = "volume") sort: String,
        @RequestParam(defaultValue = "ACTIVE") status: String
    ): ResponseEntity<KeywordsListResponse> {
        val keywordStatus = KeywordStatus.valueOf(status.uppercase())
        val keywords      = keywordRepo.findByStatus(keywordStatus)
        val metricsByKwId = metricsRepo.findByLocationCode(locationCode).associateBy { it.keywordId }

        val items = keywords
            .map { kw ->
                val metric = metricsByKwId[kw.id]
                KeywordListItem(
                    keyword          = kw.keyword,
                    searchVolume     = metric?.searchVolume,
                    cpc              = metric?.cpc,
                    competition      = metric?.competition,
                    competitionIndex = metric?.competitionIndex,
                    lastFetchedAt    = kw.lastFetchedAt?.toString()
                )
            }
            .let { list ->
                when (sort.lowercase()) {
                    "cpc"         -> list.sortedByDescending { it.cpc ?: 0.0 }
                    "competition" -> list.sortedByDescending { it.competitionIndex ?: 0 }
                    "keyword"     -> list.sortedBy { it.keyword }
                    else          -> list.sortedByDescending { it.searchVolume ?: 0 }
                }
            }

        val locationName = PolandLocations.BY_CODE[locationCode]?.canonicalName ?: "Unknown"

        return ResponseEntity.ok(
            KeywordsListResponse(
                locationCode   = locationCode,
                locationName   = locationName,
                totalKeywords  = items.size,
                keywords       = items
            )
        )
    }

    /**
     * Returns the full history for a single keyword:
     * monthly search volumes and daily trend index.
     *
     * @param keyword      keyword text (URL-encoded)
     * @param locationCode location for monthly data (default 2616 = Poland)
     * @param from         start of trend date range (YYYY-MM-DD, default: 12 months ago)
     * @param to           end of trend date range (YYYY-MM-DD, default: today)
     */
    @GetMapping("/keywords/{keyword}/history")
    fun keywordHistory(
        @PathVariable             keyword: String,
        @RequestParam(defaultValue = "2616") locationCode: Int,
        @RequestParam(required = false)      from: String?,
        @RequestParam(required = false)      to: String?
    ): ResponseEntity<KeywordHistoryResponse> {
        val kw = keywordRepo.findByKeyword(keyword)
            ?: return ResponseEntity.notFound().build()

        val fromDate = from?.let { LocalDate.parse(it) } ?: LocalDate.now().minusMonths(12)
        val toDate   = to?.let   { LocalDate.parse(it) } ?: LocalDate.now()

        val monthly  = monthlyRepo.findByKeywordAndLocation(kw.id, locationCode)
        val trends   = trendRepo.findByKeyword(kw.id, fromDate, toDate)
        val metric   = metricsRepo.findByKeywordId(kw.id).firstOrNull { it.locationCode == locationCode }

        return ResponseEntity.ok(
            KeywordHistoryResponse(
                keyword        = kw.keyword,
                locationCode   = locationCode,
                locationName   = PolandLocations.BY_CODE[locationCode]?.canonicalName ?: "Unknown",
                currentMetrics = metric?.let {
                    CurrentMetricsDto(it.searchVolume, it.cpc, it.competition, it.competitionIndex)
                },
                monthlySearches = monthly.map { MonthlyPointDto(it.year, it.month, it.searchVolume) },
                dailyTrend      = trends.map { DailyTrendPointDto(it.date.toString(), it.trendIndex) }
            )
        )
    }

    /**
     * Dashboard overview: top keywords by volume, sync statuses, location summary.
     */
    @GetMapping("/summary")
    fun summary(): ResponseEntity<DashboardSummaryResponse> {
        val active        = keywordRepo.findByStatus(KeywordStatus.ACTIVE)
        val countryMetrics = metricsRepo.findByLocationCode(PolandLocations.COUNTRY.locationCode)
        val metricsByKwId  = countryMetrics.associateBy { it.keywordId }

        val topByVolume = active
            .mapNotNull { kw -> metricsByKwId[kw.id]?.let { kw to it } }
            .sortedByDescending { (_, m) -> m.searchVolume ?: 0 }
            .take(10)
            .map { (kw, m) ->
                KeywordListItem(kw.keyword, m.searchVolume, m.cpc, m.competition, m.competitionIndex, null)
            }

        val syncStatuses = listOf(
            "INITIAL_SEED", "VOLUME_REFRESH", "TREND_FILL"
        ).mapNotNull { syncRepo.find(it) }
            .map { SyncStatusDto(it.taskName, it.status.name, it.lastSuccessAt?.toString(), it.details) }

        return ResponseEntity.ok(
            DashboardSummaryResponse(
                totalTrackedKeywords = active.size,
                topKeywordsByVolume  = topByVolume,
                syncStatuses         = syncStatuses,
                locations            = LocationsSummaryDto(
                    country          = PolandLocations.COUNTRY.canonicalName,
                    voivodeshipCount = PolandLocations.VOIVODESHIPS.size
                )
            )
        )
    }

    /**
     * Compares a single keyword across all Polish voivodeships, sorted by search volume.
     */
    @GetMapping("/voivodeships/{keyword}")
    fun voivodeshipComparison(
        @PathVariable keyword: String
    ): ResponseEntity<VoivodeshipComparisonResponse> {
        val kw = keywordRepo.findByKeyword(keyword)
            ?: return ResponseEntity.notFound().build()

        val items = metricsRepo.findByKeywordId(kw.id)
            .map { m ->
                val loc = PolandLocations.BY_CODE[m.locationCode]
                VoivodeshipMetricItem(
                    locationCode = m.locationCode,
                    locationName = loc?.canonicalName ?: "Unknown",
                    polishName   = loc?.polishName,
                    geoLevel     = if (m.locationCode == PolandLocations.COUNTRY.locationCode) "country" else "voivodeship",
                    searchVolume = m.searchVolume,
                    cpc          = m.cpc,
                    competition  = m.competition
                )
            }
            .sortedByDescending { it.searchVolume ?: 0 }

        return ResponseEntity.ok(VoivodeshipComparisonResponse(keyword = keyword, locations = items))
    }
}
