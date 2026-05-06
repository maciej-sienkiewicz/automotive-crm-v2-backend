package pl.detailing.crm.trends.scheduler

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import pl.detailing.crm.trends.domain.KeywordSource
import pl.detailing.crm.trends.domain.KeywordStatus
import pl.detailing.crm.trends.domain.KeywordMetric
import pl.detailing.crm.trends.repository.*
import pl.detailing.crm.trends.searchvolume.client.SearchVolumeClient
import pl.detailing.crm.trends.searchvolume.model.PolandLocations
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Autonomous scheduler that keeps keyword trend data fresh.
 *
 * Lifecycle:
 *   1. [onApplicationReady]    — first boot: seed keywords, full fetch (async)
 *   2. [weeklyVolumeRefresh]   — every Monday 03:00: refresh Search Volume for all active keywords
 *   3. [dailyTrendFill]        — every day 04:00: fill Trends Explore gap to today
 *
 * Rate-limit: 12 req/min on Google Ads Search Volume → 5.2 s delay between location requests.
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
        const val TASK_INITIAL_SEED    = "INITIAL_SEED"
        const val TASK_VOLUME_REFRESH  = "VOLUME_REFRESH"
        const val TASK_TREND_FILL      = "TREND_FILL"

        /** Seed keywords for the Polish auto-detailing market. */
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

    // ─── First boot ───────────────────────────────────────────────────────────

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        val status = syncRepo.find(TASK_INITIAL_SEED)
        if (status?.lastSuccessAt != null) {
            log.info("Initial seed already completed at {}. Skipping.", status.lastSuccessAt)
            return
        }
        log.info("First boot — seeding {} keywords and fetching initial metrics.", SEED_KEYWORDS.size)
        Thread(::runInitialSeed, "trends-initial-seed").start()
    }

    private fun runInitialSeed() {
        try {
            syncRepo.markRunning(TASK_INITIAL_SEED)
            seedKeywords()
            refreshVolumes()
            fillTrends()
            syncRepo.markSuccess(TASK_INITIAL_SEED, "${SEED_KEYWORDS.size} keywords seeded and fetched")
            log.info("Initial seed completed successfully.")
        } catch (ex: Exception) {
            log.error("Initial seed failed.", ex)
            syncRepo.markFailed(TASK_INITIAL_SEED, ex.message)
        }
    }

    // ─── Weekly volume refresh ─────────────────────────────────────────────────

    @Scheduled(cron = "0 0 3 * * MON")
    fun weeklyVolumeRefresh() {
        if (isAlreadyRunning(TASK_VOLUME_REFRESH)) {
            log.warn("Volume refresh already running — skipping.")
            return
        }
        try {
            syncRepo.markRunning(TASK_VOLUME_REFRESH)
            refreshVolumes()
            syncRepo.markSuccess(TASK_VOLUME_REFRESH)
        } catch (ex: Exception) {
            log.error("Weekly volume refresh failed.", ex)
            syncRepo.markFailed(TASK_VOLUME_REFRESH, ex.message)
        }
    }

    // ─── Daily trend fill ──────────────────────────────────────────────────────

    @Scheduled(cron = "0 0 4 * * *")
    fun dailyTrendFill() {
        if (isAlreadyRunning(TASK_TREND_FILL)) {
            log.warn("Trend fill already running — skipping.")
            return
        }
        try {
            syncRepo.markRunning(TASK_TREND_FILL)
            fillTrends()
            syncRepo.markSuccess(TASK_TREND_FILL)
        } catch (ex: Exception) {
            log.error("Daily trend fill failed.", ex)
            syncRepo.markFailed(TASK_TREND_FILL, ex.message)
        }
    }

    // ─── Core logic ────────────────────────────────────────────────────────────

    private fun seedKeywords() {
        SEED_KEYWORDS.forEach { kw ->
            val id = keywordRepo.insertIfNotExists(kw, KeywordSource.SEED)
            keywordRepo.updateStatus(id, KeywordStatus.ACTIVE)
        }
        log.info("Seeded {} keywords.", SEED_KEYWORDS.size)
    }

    private fun refreshVolumes() {
        val activeKeywords = keywordRepo.findByStatus(KeywordStatus.ACTIVE)
        if (activeKeywords.isEmpty()) {
            log.info("No active keywords to refresh.")
            return
        }

        val keywordTexts = activeKeywords.map { it.keyword }
        val idByKeyword  = activeKeywords.associate { it.keyword to it.id }
        val allLocations = listOf(PolandLocations.COUNTRY) + PolandLocations.VOIVODESHIPS

        allLocations.forEachIndexed { index, location ->
            if (index > 0) Thread.sleep(SearchVolumeClient.RATE_LIMIT_DELAY_MS)

            log.info("Fetching Search Volume for {} ({}/{})", location.canonicalName, index + 1, allLocations.size)
            try {
                keywordTexts
                    .chunked(SearchVolumeClient.MAX_KEYWORDS_PER_SEARCH_VOLUME_REQUEST)
                    .forEach { batch ->
                        val response = client.fetchSearchVolume(location.locationCode, batch)
                        response.tasks
                            ?.flatMap { it.result.orEmpty() }
                            ?.forEach { item ->
                                val kw   = item.keyword ?: return@forEach
                                val kwId = idByKeyword[kw] ?: return@forEach

                                metricsRepo.upsert(
                                    KeywordMetric(
                                        id = 0,
                                        keywordId = kwId,
                                        locationCode = location.locationCode,
                                        searchVolume = item.searchVolume,
                                        cpc = item.cpc,
                                        competition = item.competition,
                                        competitionIndex = item.competitionIndex,
                                        lowTopOfPageBid = item.lowTopOfPageBid,
                                        highTopOfPageBid = item.highTopOfPageBid,
                                        fetchedAt = Instant.now()
                                    )
                                )

                                item.monthlySearches?.forEach { ms ->
                                    val y = ms.year  ?: return@forEach
                                    val m = ms.month ?: return@forEach
                                    monthlyRepo.upsert(kwId, location.locationCode, y, m, ms.searchVolume)
                                }

                                keywordRepo.updateLastFetched(kwId)
                            }
                    }
            } catch (ex: Exception) {
                log.error("Failed to fetch volume for {} — continuing with next location.", location.canonicalName, ex)
            }
        }

        log.info("Volume refresh done: {} keywords × {} locations.", activeKeywords.size, allLocations.size)
    }

    private fun fillTrends() {
        val activeKeywords = keywordRepo.findByStatus(KeywordStatus.ACTIVE)
        if (activeKeywords.isEmpty()) return

        val today    = LocalDate.now()
        val gapFrom  = computeGapFrom(activeKeywords.map { it.id }, today)

        if (!gapFrom.isBefore(today)) {
            log.info("Trend data is up to date — no gap to fill.")
            return
        }

        val dateFrom = gapFrom.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val dateTo   = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        log.info("Filling trends gap {} → {} for {} keywords.", dateFrom, dateTo, activeKeywords.size)

        val idByKeyword = activeKeywords.associate { it.keyword to it.id }
        val batches     = activeKeywords.map { it.keyword }
            .chunked(SearchVolumeClient.MAX_KEYWORDS_PER_TRENDS_REQUEST)

        batches.forEachIndexed { batchIndex, batch ->
            if (batchIndex > 0) Thread.sleep(2_000)
            try {
                val response = client.fetchTrendsExplore(batch, dateFrom, dateTo)
                response.tasks?.forEach { task ->
                    task.result?.forEach { exploreResult ->
                        val keywords = exploreResult.keywords ?: return@forEach
                        exploreResult.items?.forEach { item ->
                            item.data?.forEach { point ->
                                val rawDate = point.dateFrom ?: return@forEach
                                val values  = point.values  ?: return@forEach
                                val date    = LocalDate.parse(rawDate)

                                keywords.forEachIndexed { idx, keyword ->
                                    val kwId = idByKeyword[keyword] ?: return@forEachIndexed
                                    trendRepo.upsert(kwId, date, values.getOrNull(idx))
                                }
                            }
                        }
                    }
                }
                log.info("Trends batch {}/{} OK.", batchIndex + 1, batches.size)
            } catch (ex: Exception) {
                log.error("Trends batch {}/{} failed.", batchIndex + 1, batches.size, ex)
            }
        }

        log.info("Trend fill complete.")
    }

    /**
     * Determines the earliest date from which trend data should be fetched.
     *
     * For each keyword, the gap starts on the first day of the month after its last known
     * monthly search entry. We take the minimum across all keywords so that no keyword
     * is left with a data gap in the trend chart.
     *
     * Falls back to 3 months ago if no monthly data exists yet.
     */
    private fun computeGapFrom(keywordIds: List<Long>, today: LocalDate): LocalDate {
        val default = today.minusMonths(3)
        return keywordIds
            .mapNotNull { id -> monthlyRepo.findLatestMonth(id) }
            .map { (year, month) -> YearMonth.of(year, month).plusMonths(1).atDay(1) }
            .minOrNull()
            ?: default
    }

    private fun isAlreadyRunning(taskName: String): Boolean =
        syncRepo.find(taskName)?.status?.name == "RUNNING"
}
