package pl.detailing.crm.trends.domain

import java.time.Instant
import java.time.LocalDate

data class TrackedKeyword(
    val id: Long,
    val keyword: String,
    val status: KeywordStatus,
    val source: KeywordSource,
    val addedAt: Instant,
    val lastFetchedAt: Instant?,
    val relevanceScore: Double?
)

data class KeywordMetric(
    val id: Long,
    val keywordId: Long,
    val locationCode: Int,
    val searchVolume: Int?,
    val cpc: Double?,
    val competition: String?,
    val competitionIndex: Int?,
    val lowTopOfPageBid: Double?,
    val highTopOfPageBid: Double?,
    val fetchedAt: Instant
)

data class MonthlySearch(
    val id: Long,
    val keywordId: Long,
    val locationCode: Int,
    val year: Int,
    val month: Int,
    val searchVolume: Int?
)

data class TrendDataPoint(
    val id: Long,
    val keywordId: Long,
    val date: LocalDate,
    val trendIndex: Int?,
    val locationCode: Int
)

data class SyncStatus(
    val taskName: String,
    val lastRunAt: Instant?,
    val lastSuccessAt: Instant?,
    val status: SyncTaskStatus,
    val details: String?
)

enum class KeywordStatus { PENDING, ACTIVE, IGNORED }
enum class KeywordSource { SEED, EXPANDED, MANUAL }
enum class SyncTaskStatus { IDLE, RUNNING, FAILED }
