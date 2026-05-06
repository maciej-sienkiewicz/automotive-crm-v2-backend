package pl.detailing.crm.trends.controller.dto

// ─── GET /api/trends/keywords ─────────────────────────────────────────────────

data class KeywordsListResponse(
    val locationCode: Int,
    val locationName: String,
    val totalKeywords: Int,
    val keywords: List<KeywordTrendSummary>
)

/**
 * Per-keyword entry in the list view.
 *
 * growthRate       — % change: avg(last 30 days trend) vs avg(previous 30 days trend)
 * latestTrendIndex — most recent daily value (0–100 relative index, same scale as Google Trends)
 * trendDirection   — "GROWING" (+10%), "DECLINING" (-10%), or "STABLE"
 *
 * Raw search volume is intentionally omitted — Google buckets it into ~40 steps,
 * making exact numbers misleading. Use the trend and growth rate instead.
 */
data class KeywordTrendSummary(
    val keyword: String,
    val trendDirection: String,
    val growthRate: Double?,
    val latestTrendIndex: Int?,
    val competition: String?,
    val competitionIndex: Int?,
    val cpc: Double?,
    val relevanceScore: Double?
)

// ─── GET /api/trends/keywords/{keyword}/history ───────────────────────────────

/**
 * Full trend detail for a single keyword.
 *
 * timeline — unified 0-100 series combining:
 *   • monthly: absolute volumes normalized relative to peak month (peak = 100)
 *   • daily:   raw Trends Explore index (already 0-100), covering the gap to today
 *
 * growthRates — computed from monthly_searches (absolute Google Ads volumes):
 *   • monthOverMonth    : last month vs previous month
 *   • quarterOverQuarter: avg(last 3 months) vs avg(prev 3 months)
 *   • yearOverYear      : last month vs same month one year ago
 */
data class KeywordTrendDetailResponse(
    val keyword: String,
    val locationCode: Int,
    val locationName: String,
    val trendDirection: String,
    val competition: String?,
    val competitionIndex: Int?,
    val cpc: Double?,
    val growthRates: GrowthRatesDto,
    val timeline: List<TimelinePointDto>
)

data class GrowthRatesDto(
    val monthOverMonth: Double?,
    val quarterOverQuarter: Double?,
    val yearOverYear: Double?
)

data class TimelinePointDto(
    /** ISO date: YYYY-MM-DD (monthly points use the 1st of the month). */
    val date: String,
    /** Normalized 0-100 index. Both monthly and daily are on the same scale. */
    val trendIndex: Int,
    /** "monthly" (from Google Ads) or "daily" (from Trends Explore). */
    val granularity: String
)

// ─── GET /api/trends/summary ──────────────────────────────────────────────────

data class DashboardSummaryResponse(
    val totalTrackedKeywords: Int,
    val topKeywordsByVolume: List<KeywordTrendSummary>,
    val syncStatuses: List<SyncStatusDto>,
    val locations: LocationsSummaryDto
)

data class SyncStatusDto(
    val taskName: String,
    val status: String,
    val lastSuccessAt: String?,
    val details: String?
)

data class LocationsSummaryDto(val country: String, val voivodeshipCount: Int)

// ─── GET /api/trends/locations ────────────────────────────────────────────────

data class LocationsResponse(
    val country: LocationItem,
    val voivodeships: List<LocationItem>
)

data class LocationItem(
    val locationCode: Int,
    val canonicalName: String,
    val polishName: String
)

// ─── GET /api/trends/voivodeships/{keyword} ───────────────────────────────────

data class VoivodeshipComparisonResponse(
    val keyword: String,
    val locations: List<VoivodeshipMetricItem>
)

data class VoivodeshipMetricItem(
    val locationCode: Int,
    val locationName: String,
    val polishName: String?,
    val geoLevel: String,
    val growthRate: Double?,
    val latestTrendIndex: Int?,
    val trendDirection: String,
    val competition: String?,
    val cpc: Double?
)
