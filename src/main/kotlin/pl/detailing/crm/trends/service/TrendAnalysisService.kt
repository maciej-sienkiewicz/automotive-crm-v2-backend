package pl.detailing.crm.trends.service

import org.springframework.stereotype.Service
import pl.detailing.crm.trends.controller.dto.*
import pl.detailing.crm.trends.domain.KeywordMetric
import pl.detailing.crm.trends.domain.TrackedKeyword
import pl.detailing.crm.trends.repository.MonthlySearchRepository
import pl.detailing.crm.trends.repository.TrendDataRepository
import pl.detailing.crm.trends.searchvolume.model.PolandLocations
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.roundToInt

/**
 * Computes trend summaries and growth rates from stored monthly and daily data.
 *
 * Two layers of analysis:
 *
 *  LIST (batch, efficient):
 *    — latestTrendIndex  : most recent daily value from trend_data
 *    — growthRate        : avg(last 30d) vs avg(prev 30d) from trend_data
 *    — trendDirection    : derived from growthRate
 *
 *  DETAIL (per keyword):
 *    — growthRates.monthOverMonth    : last month vs previous month (monthly_searches)
 *    — growthRates.quarterOverQuarter: avg last 3m vs avg prev 3m (monthly_searches)
 *    — growthRates.yearOverYear      : last month vs same month -1y (monthly_searches)
 *    — timeline: normalized monthly (0-100) + raw daily (0-100), unified scale
 */
@Service
class TrendAnalysisService(
    private val monthlyRepo: MonthlySearchRepository,
    private val trendRepo: TrendDataRepository
) {
    companion object {
        private const val GROWING_THRESHOLD  =  10.0   // % above = GROWING
        private const val DECLINING_THRESHOLD = -10.0  // % below = DECLINING
    }

    // ─── LIST: batch summaries ─────────────────────────────────────────────────

    fun buildTrendSummaries(
        keywords: List<TrackedKeyword>,
        metrics: Map<Long, KeywordMetric>,
        locationCode: Int
    ): List<KeywordTrendSummary> {
        if (keywords.isEmpty()) return emptyList()

        val ids      = keywords.map { it.id }
        val today    = LocalDate.now()

        // Batch: latest daily trend index per keyword
        val latestIndex = trendRepo.findLatestIndexByKeywords(ids, TREND_LOCATION)

        // Batch: avg trend index last 30 days vs previous 30 days
        val recentAvg = trendRepo.findAverageIndexByKeywords(ids, today.minusDays(30), today, TREND_LOCATION)
        val prevAvg   = trendRepo.findAverageIndexByKeywords(ids, today.minusDays(60), today.minusDays(31), TREND_LOCATION)

        return keywords.map { kw ->
            val metric      = metrics[kw.id]
            val growthRate  = computeGrowthRate(recentAvg[kw.id], prevAvg[kw.id])

            KeywordTrendSummary(
                keyword          = kw.keyword,
                trendDirection   = trendDirection(growthRate),
                growthRate       = growthRate,
                latestTrendIndex = latestIndex[kw.id],
                competition      = metric?.competition,
                competitionIndex = metric?.competitionIndex,
                cpc              = metric?.cpc,
                relevanceScore   = kw.relevanceScore
            )
        }
    }

    // ─── DETAIL: per-keyword timeline + growth rates ──────────────────────────

    fun buildTrendDetail(
        kw: TrackedKeyword,
        locationCode: Int
    ): KeywordTrendDetailResponse {
        val metric   = null  // caller fetches and passes separately — see controller
        val monthly  = monthlyRepo.findByKeywordAndLocation(kw.id, locationCode)
        val daily    = fetchDailyGap(kw.id, monthly)
        val timeline = buildTimeline(monthly.map { Triple(it.year, it.month, it.searchVolume) }, daily)
        val rates    = computeMonthlyGrowthRates(monthly.map { it.searchVolume })

        return KeywordTrendDetailResponse(
            keyword        = kw.keyword,
            locationCode   = locationCode,
            locationName   = PolandLocations.BY_CODE[locationCode]?.canonicalName ?: "Unknown",
            trendDirection = trendDirection(rates.monthOverMonth),
            growthRates    = rates,
            timeline       = timeline
        )
    }

    fun buildTrendDetailWithMetrics(
        kw: TrackedKeyword,
        locationCode: Int,
        metric: KeywordMetric?
    ): KeywordTrendDetailResponse {
        val monthly  = monthlyRepo.findByKeywordAndLocation(kw.id, locationCode)
        val daily    = fetchDailyGap(kw.id, monthly)
        val timeline = buildTimeline(monthly.map { Triple(it.year, it.month, it.searchVolume) }, daily)
        val rates    = computeMonthlyGrowthRates(monthly.map { it.searchVolume })

        return KeywordTrendDetailResponse(
            keyword        = kw.keyword,
            locationCode   = locationCode,
            locationName   = PolandLocations.BY_CODE[locationCode]?.canonicalName ?: "Unknown",
            trendDirection = trendDirection(rates.monthOverMonth),
            competition    = metric?.competition,
            competitionIndex = metric?.competitionIndex,
            cpc            = metric?.cpc,
            growthRates    = rates,
            timeline       = timeline
        )
    }

    // ─── Timeline construction ─────────────────────────────────────────────────

    /**
     * Builds a unified 0-100 timeline:
     *   - Monthly: volumes normalized relative to peak (peak month = 100)
     *   - Daily:   raw trend_index (already 0-100) appended after the last monthly point
     *
     * Both segments are on the same scale so the frontend can draw them as one continuous line.
     */
    private fun buildTimeline(
        monthly: List<Triple<Int, Int, Int?>>,   // (year, month, searchVolume)
        daily: List<Pair<LocalDate, Int?>>        // (date, trendIndex)
    ): List<TimelinePointDto> {
        val peak = monthly.mapNotNull { it.third }.maxOrNull()?.toDouble() ?: 0.0

        val monthlyPoints = monthly.map { (year, month, volume) ->
            val normalized = if (peak > 0 && volume != null)
                ((volume / peak) * 100).roundToInt().coerceIn(0, 100)
            else 0
            TimelinePointDto(
                date        = LocalDate.of(year, month, 1).toString(),
                trendIndex  = normalized,
                granularity = "monthly"
            )
        }

        val dailyPoints = daily.mapNotNull { (date, index) ->
            index ?: return@mapNotNull null
            TimelinePointDto(date = date.toString(), trendIndex = index, granularity = "daily")
        }

        return monthlyPoints + dailyPoints
    }

    private fun fetchDailyGap(
        keywordId: Long,
        monthly: List<pl.detailing.crm.trends.domain.MonthlySearch>
    ): List<Pair<LocalDate, Int?>> {
        val gapFrom = monthly.lastOrNull()
            ?.let { YearMonth.of(it.year, it.month).plusMonths(1).atDay(1) }
            ?: LocalDate.now().minusMonths(3)

        return trendRepo.findByKeyword(keywordId, gapFrom, LocalDate.now())
            .map { it.date to it.trendIndex }
    }

    // ─── Growth rate computations ──────────────────────────────────────────────

    /** Short-term: avg(recent period) vs avg(previous period), both from trend_data. */
    private fun computeGrowthRate(recent: Double?, previous: Double?): Double? {
        if (recent == null || previous == null || previous == 0.0) return null
        return ((recent - previous) / previous * 100).round(1)
    }

    /**
     * Long-term growth rates from monthly_searches absolute volumes.
     *
     * MoM  — last month vs previous month
     * QoQ  — avg(last 3 months) vs avg(previous 3 months)
     * YoY  — last month vs same month one year ago
     */
    private fun computeMonthlyGrowthRates(volumes: List<Int?>): GrowthRatesDto {
        val v = volumes  // ordered oldest → newest

        val mom = if (v.size >= 2)
            computeGrowthRate(v.last()?.toDouble(), v.dropLast(1).last()?.toDouble())
        else null

        val qoq = if (v.size >= 6) {
            val current = v.takeLast(3).mapNotNull { it }.average().takeIf { it.isFinite() }
            val prev    = v.dropLast(3).takeLast(3).mapNotNull { it }.average().takeIf { it.isFinite() }
            computeGrowthRate(current, prev)
        } else null

        val yoy = if (v.size >= 13)
            computeGrowthRate(v.last()?.toDouble(), v[v.size - 13].toDouble())
        else if (v.size == 12)
            computeGrowthRate(v.last()?.toDouble(), v.first()?.toDouble())
        else null

        return GrowthRatesDto(monthOverMonth = mom, quarterOverQuarter = qoq, yearOverYear = yoy)
    }

    private fun trendDirection(growthRate: Double?): String = when {
        growthRate == null          -> "STABLE"
        growthRate > GROWING_THRESHOLD   -> "GROWING"
        growthRate < DECLINING_THRESHOLD -> "DECLINING"
        else                        -> "STABLE"
    }

    private fun Double.round(decimals: Int): Double {
        val factor = Math.pow(10.0, decimals.toDouble())
        return (this * factor).roundToInt() / factor
    }
}

private const val TREND_LOCATION = TrendDataRepository.POLAND_LOCATION_CODE
