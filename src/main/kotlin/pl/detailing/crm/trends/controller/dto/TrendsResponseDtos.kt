package pl.detailing.crm.trends.controller.dto

// ─── GET /api/trends/keywords ─────────────────────────────────────────────────

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
    val relevanceScore: Double?,
    val lastFetchedAt: String?
)

// ─── GET /api/trends/keywords/{keyword}/history ───────────────────────────────

data class KeywordHistoryResponse(
    val keyword: String,
    val locationCode: Int,
    val locationName: String,
    val currentMetrics: CurrentMetricsDto?,
    val monthlySearches: List<MonthlyPointDto>,
    val dailyTrend: List<DailyTrendPointDto>
)

data class CurrentMetricsDto(
    val searchVolume: Int?,
    val cpc: Double?,
    val competition: String?,
    val competitionIndex: Int?
)

data class MonthlyPointDto(val year: Int, val month: Int, val searchVolume: Int?)

data class DailyTrendPointDto(val date: String, val trendIndex: Int?)

// ─── GET /api/trends/summary ──────────────────────────────────────────────────

data class DashboardSummaryResponse(
    val totalTrackedKeywords: Int,
    val topKeywordsByVolume: List<KeywordListItem>,
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
    val searchVolume: Int?,
    val cpc: Double?,
    val competition: String?
)
