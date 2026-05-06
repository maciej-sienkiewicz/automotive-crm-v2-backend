package com.example.demo.trends.scheduler

import com.example.demo.trends.repository.*
import com.example.demo.trends.searchvolume.client.SearchVolumeClient
import com.example.demo.trends.searchvolume.model.PolandLocations
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Autonomous scheduler for keyword monitoring.
 *
 * Lifecycle:
 *   1. On first start (ApplicationReady): seeds keywords, fetches all metrics
 *   2. Weekly (Monday 3AM): refreshes search volumes for all active keywords
 *   3. Daily (4AM): fills trend data gap (Trends Explore) from last monthly to today
 *
 * Rate-limit aware: 12 req/min for Google Ads → ~5.2s between requests.
 */
@Component
class KeywordSyncScheduler(
    private val keywordRepo: TrackedKeywordRepository,
    private val metricsRepo: KeywordMetricsRepository,
    private val monthlyRepo: MonthlySearchRepository,
    private val trendRepo: TrendDataRepository,
    private val syncRepo: SyncStatusRepository,
    private val client: SearchVolumeClient
) {
    private val log = LoggerFactory.getLogger(KeywordSyncScheduler::class.java)

    companion object {
        const val TASK_INITIAL_SEED = "INITIAL_SEED"
        const val TASK_VOLUME_REFRESH = "VOLUME_REFRESH"
        const val TASK_TREND_FILL = "TREND_FILL"

        /** Initial seed keywords for auto-detailing industry (Polish market). */
        val SEED_KEYWORDS = listOf(
            "auto detailing",
            "detailing samochodowy",
            "myjnia samochodowa",
            "myjnia bezdotykowa",
            "powłoka ceramiczna",
            "polerowanie lakieru",
            "folia PPF",
            "czyszczenie tapicerki",
            "pranie tapicerki samochodowej",
            "woskowanie samochodu",
            "korekta lakieru",
            "zabezpieczenie lakieru",
            "ceramika samochodowa",
            "detailing wnętrza",
            "mycie parowe samochodu",
            "renowacja lakieru",
            "folia ochronna na samochód",
            "czyszczenie skóry w samochodzie",
            "polerka samochodowa",
            "pasta polerska",
            "glinka do lakieru",
            "odżywka do plastików",
            "szampon samochodowy",
            "suszenie samochodu",
            "nano powłoka",
            "hydrofobowa powłoka",
            "car wrapping",
            "oklejanie samochodu",
            "przyciemnianie szyb",
            "detailing Warszawa"
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // FIRST START — seed + full fetch
    // ═══════════════════════════════════════════════════════════════

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        val sync = syncRepo.get(TASK_INITIAL_SEED)
        if (sync?.lastSuccessAt != null) {
            log.info("Initial seed already completed at {}. Skipping.", sync.lastSuccessAt)
            return
        }

        log.info("First start detected — seeding {} keywords and fetching metrics", SEED_KEYWORDS.size)
        Thread { runInitialSeed() }.start()  // Run async to not block startup
    }

    private fun runInitialSeed() {
        try {
            syncRepo.markRunning(TASK_INITIAL_SEED)

            // 1. Insert seed keywords
            val keywordIds = SEED_KEYWORDS.map { kw ->
                val id = keywordRepo.insertIfNotExists(kw, "SEED")
                keywordRepo.updateStatus(id, "ACTIVE")
                id
            }
            log.info("Seeded {} keywords", keywordIds.size)

            // 2. Fetch volumes for country + all voivodeships
            refreshVolumes()

            // 3. Fill trends
            fillTrends()

            syncRepo.markSuccess(TASK_INITIAL_SEED, "${SEED_KEYWORDS.size} keywords seeded and fetched")
            log.info("Initial seed completed successfully")
        } catch (ex: Exception) {
            log.error("Initial seed failed", ex)
            syncRepo.markFailed(TASK_INITIAL_SEED, ex.message)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // WEEKLY — refresh search volumes
    // ═══════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 0 3 * * MON")
    fun weeklyVolumeRefresh() {
        if (isRunning(TASK_VOLUME_REFRESH)) {
            log.warn("Volume refresh already running — skipping")
            return
        }
        try {
            syncRepo.markRunning(TASK_VOLUME_REFRESH)
            refreshVolumes()
            syncRepo.markSuccess(TASK_VOLUME_REFRESH)
        } catch (ex: Exception) {
            log.error("Weekly volume refresh failed", ex)
            syncRepo.markFailed(TASK_VOLUME_REFRESH, ex.message)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // DAILY — fill trend gap
    // ═══════════════════════════════════════════════════════════════

    @Scheduled(cron = "0 0 4 * * *")
    fun dailyTrendFill() {
        if (isRunning(TASK_TREND_FILL)) {
            log.warn("Trend fill already running — skipping")
            return
        }
        try {
            syncRepo.markRunning(TASK_TREND_FILL)
            fillTrends()
            syncRepo.markSuccess(TASK_TREND_FILL)
        } catch (ex: Exception) {
            log.error("Daily trend fill failed", ex)
            syncRepo.markFailed(TASK_TREND_FILL, ex.message)
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // CORE LOGIC
    // ═══════════════════════════════════════════════════════════════

    private fun refreshVolumes() {
        val activeKeywords = keywordRepo.findByStatus("ACTIVE")
        if (activeKeywords.isEmpty()) {
            log.info("No active keywords to refresh")
            return
        }

        val keywordTexts = activeKeywords.map { it.keyword }
        val keywordIdMap = activeKeywords.associate { it.keyword to it.id }

        // All locations: country + 16 voivodeships = 17 requests
        val allLocations = listOf(PolandLocations.COUNTRY) + PolandLocations.VOIVODESHIPS

        for ((locIndex, location) in allLocations.withIndex()) {
            if (locIndex > 0) {
                Thread.sleep(SearchVolumeClient.RATE_LIMIT_DELAY_MS)
            }

            try {
                log.info("Fetching volume for {} ({}/{})", location.canonicalName, locIndex + 1, allLocations.size)

                // Batch keywords (max 1000 per request)
                for (batch in keywordTexts.chunked(SearchVolumeClient.MAX_KEYWORDS_PER_REQUEST)) {
                    val (response, _) = client.fetchSearchVolume(location.locationCode, batch)

                    response.tasks?.flatMap { it.result ?: emptyList() }?.forEach { item ->
                        val kw = item.keyword ?: return@forEach
                        val kwId = keywordIdMap[kw] ?: return@forEach

                        // Upsert metric
                        metricsRepo.upsert(
                            KeywordMetricEntity(
                                id = 0,
                                keywordId = kwId,
                                locationCode = location.locationCode,
                                searchVolume = item.searchVolume,
                                cpc = item.cpc,
                                competition = item.competition,
                                competitionIndex = item.competitionIndex,
                                lowTopOfPageBid = item.lowTopOfPageBid,
                                highTopOfPageBid = item.highTopOfPageBid,
                                fetchedAt = java.time.Instant.now()
                            )
                        )

                        // Upsert monthly searches
                        item.monthlySearches?.forEach { ms ->
                            val y = ms.year ?: return@forEach
                            val m = ms.month ?: return@forEach
                            monthlyRepo.upsert(kwId, location.locationCode, y, m, ms.searchVolume)
                        }

                        keywordRepo.updateLastFetched(kwId)
                    }
                }
            } catch (ex: Exception) {
                log.error("Failed to fetch volume for {}: {}", location.canonicalName, ex.message)
                // Continue with next location — partial failure acceptable
            }
        }

        log.info("Volume refresh completed for {} keywords across {} locations",
            activeKeywords.size, allLocations.size)
    }

    private fun fillTrends() {
        val activeKeywords = keywordRepo.findByStatus("ACTIVE")
        if (activeKeywords.isEmpty()) return

        val today = LocalDate.now()

        // Find the gap: from last monthly data to today
        // Use the earliest "latest month" across all keywords as gap start
        var gapFrom = today.minusMonths(3)  // default: 3 months back if no monthly data

        for (kw in activeKeywords) {
            val latestMonth = monthlyRepo.findLatestMonth(kw.id)
            if (latestMonth != null) {
                val nextMonth = YearMonth.of(latestMonth.first, latestMonth.second).plusMonths(1).atDay(1)
                if (nextMonth.isAfter(gapFrom)) {
                    // Use the tightest gap (latest data = smallest gap)
                } else {
                    gapFrom = nextMonth
                }
            }
        }

        if (!gapFrom.isBefore(today)) {
            log.info("No trend gap to fill — data is up to date")
            return
        }

        val dateFrom = gapFrom.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val dateTo = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        log.info("Filling trends gap: {} → {} for {} keywords", dateFrom, dateTo, activeKeywords.size)

        val keywordTexts = activeKeywords.map { it.keyword }
        val keywordIdMap = activeKeywords.associate { it.keyword to it.id }

        // Trends Explore: max 5 keywords per request
        for ((batchIndex, batch) in keywordTexts.chunked(SearchVolumeClient.MAX_TRENDS_KEYWORDS_PER_REQUEST).withIndex()) {
            try {
                if (batchIndex > 0) Thread.sleep(2000) // gentle rate limiting

                val (response, _) = client.fetchTrendsExplore(batch, dateFrom, dateTo)

                response.tasks?.forEach { task ->
                    task.result?.forEach { exploreResult ->
                        val kws = exploreResult.keywords ?: return@forEach
                        exploreResult.items?.forEach { item ->
                            item.data?.forEach { dataPoint ->
                                val date = dataPoint.dateFrom ?: return@forEach
                                val values = dataPoint.values ?: return@forEach
                                val parsedDate = LocalDate.parse(date)

                                for ((idx, keyword) in kws.withIndex()) {
                                    val value = values.getOrNull(idx)
                                    val kwId = keywordIdMap[keyword] ?: continue
                                    trendRepo.upsert(kwId, parsedDate, value)
                                }
                            }
                        }
                    }
                }

                log.info("Trends batch {}/{} OK", batchIndex + 1,
                    (keywordTexts.size + 4) / 5)
            } catch (ex: Exception) {
                log.error("Trends batch {} failed: {}", batchIndex + 1, ex.message)
            }
        }

        log.info("Trend fill completed")
    }

    private fun isRunning(taskName: String): Boolean {
        val sync = syncRepo.get(taskName)
        return sync?.status == "RUNNING"
    }
}

