package pl.detailing.crm.trends.expansion

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.trends.domain.KeywordSource
import pl.detailing.crm.trends.domain.KeywordStatus
import pl.detailing.crm.trends.repository.*
import pl.detailing.crm.trends.searchvolume.model.PolandLocations
import pl.detailing.crm.trends.searchvolume.client.SearchVolumeClient

/**
 * Orchestrates monthly keyword expansion.
 *
 * Flow:
 *   1. Use all ACTIVE keywords as seeds → call keywords_for_keywords (batches of 20)
 *   2. Build a unified scoring pool:
 *        - New candidates: metrics from the API response
 *        - Existing tracked keywords: metrics from the keyword_metrics table (fallback)
 *   3. Score every keyword via [KeywordRelevanceScorer]
 *   4. Top [TOP_KEYWORDS_LIMIT] by score → ACTIVE
 *      Remaining → IGNORED  (they can be re-promoted in the next expansion run)
 *   5. Persist scores and statuses; insert genuinely new keywords with source=EXPANDED
 */
@Service
class KeywordExpansionService(
    private val keywordRepo: TrackedKeywordRepository,
    private val metricsRepo: KeywordMetricsRepository,
    private val expansionClient: KeywordExpansionClient
) {
    private val log = LoggerFactory.getLogger(KeywordExpansionService::class.java)

    companion object {
        const val TOP_KEYWORDS_LIMIT = 100
        private const val POLAND_LOCATION_CODE = PolandLocations.COUNTRY.locationCode
    }

    fun expand() {
        val seeds = keywordRepo.findByStatus(KeywordStatus.ACTIVE).map { it.keyword }
        if (seeds.isEmpty()) {
            log.info("No active keywords to use as seeds — skipping expansion.")
            return
        }

        log.info("Starting expansion from {} seed keywords.", seeds.size)

        // ── 1. Fetch related keywords from DataForSEO ────────────────────────
        val apiResults = fetchAllCandidates(seeds)
        log.info("API returned {} keyword candidates.", apiResults.size)

        // ── 2. Build scoring pool ─────────────────────────────────────────────
        // API results take precedence (fresh data); existing metrics fill the gaps.
        val scoringPool = buildScoringPool(apiResults)
        log.info("Scoring pool: {} keywords (API + existing).", scoringPool.size)

        // ── 3. Score ──────────────────────────────────────────────────────────
        val scores = KeywordRelevanceScorer.scoreAll(scoringPool)

        // ── 4. Select top-N ───────────────────────────────────────────────────
        val top100 = scores.entries
            .sortedByDescending { it.value }
            .take(TOP_KEYWORDS_LIMIT)
            .map { it.key }
            .toSet()

        log.info("Top {} selected. {} keywords will be ACTIVE, {} IGNORED.",
            TOP_KEYWORDS_LIMIT, top100.size, scores.size - top100.size)

        // ── 5. Persist ────────────────────────────────────────────────────────
        val existingByKeyword = keywordRepo.findAll().associateBy { it.keyword }
        var promoted = 0; var demoted = 0; var inserted = 0

        scoringPool.keys.forEach { keyword ->
            val score        = scores[keyword] ?: 0.0
            val targetStatus = if (keyword in top100) KeywordStatus.ACTIVE else KeywordStatus.IGNORED
            val existing     = existingByKeyword[keyword]

            if (existing != null) {
                if (existing.status != targetStatus) {
                    keywordRepo.updateStatus(existing.id, targetStatus)
                    if (targetStatus == KeywordStatus.ACTIVE) promoted++ else demoted++
                }
                keywordRepo.updateRelevanceScore(existing.id, score)
            } else {
                val id = keywordRepo.insertIfNotExists(keyword, KeywordSource.EXPANDED)
                keywordRepo.updateStatus(id, targetStatus)
                keywordRepo.updateRelevanceScore(id, score)
                inserted++
                if (targetStatus == KeywordStatus.ACTIVE) promoted++
            }
        }

        log.info("Expansion complete — inserted: {}, promoted: {}, demoted: {}.",
            inserted, promoted, demoted)
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Calls keywords_for_keywords in batches of [KeywordExpansionClient.MAX_SEEDS_PER_REQUEST].
     * Returns a flat map of keyword → scoring input from the API response.
     */
    private fun fetchAllCandidates(seeds: List<String>): Map<String, KeywordRelevanceScorer.Input> {
        val result = mutableMapOf<String, KeywordRelevanceScorer.Input>()

        seeds.chunked(KeywordExpansionClient.MAX_SEEDS_PER_REQUEST).forEachIndexed { index, batch ->
            if (index > 0) Thread.sleep(SearchVolumeClient.RATE_LIMIT_DELAY_MS)
            try {
                val response = expansionClient.fetchRelatedKeywords(batch)
                response.tasks
                    ?.flatMap { it.result.orEmpty() }
                    ?.forEach { item ->
                        val kw = item.keyword ?: return@forEach
                        result[kw] = KeywordRelevanceScorer.Input(
                            searchVolume     = item.searchVolume,
                            cpc              = item.cpc,
                            competitionIndex = item.competitionIndex
                        )
                    }
                log.info("Expansion batch {}/{} — {} candidates so far.",
                    index + 1, (seeds.size + KeywordExpansionClient.MAX_SEEDS_PER_REQUEST - 1) /
                        KeywordExpansionClient.MAX_SEEDS_PER_REQUEST, result.size)
            } catch (ex: Exception) {
                log.error("Expansion batch {} failed — continuing.", index + 1, ex)
            }
        }

        return result
    }

    /**
     * Merges API results with metrics from the database.
     *
     * Any tracked keyword not returned by the API (e.g. a seed that DataForSEO
     * didn't echo back) still participates in scoring via its stored keyword_metrics.
     */
    private fun buildScoringPool(
        apiCandidates: Map<String, KeywordRelevanceScorer.Input>
    ): Map<String, KeywordRelevanceScorer.Input> {
        val pool = apiCandidates.toMutableMap()

        val existingMetrics = metricsRepo
            .findByLocationCode(POLAND_LOCATION_CODE)
            .associateBy { it.keywordId }

        keywordRepo.findAll().forEach { kw ->
            if (kw.keyword !in pool) {
                val metric = existingMetrics[kw.id]
                pool[kw.keyword] = KeywordRelevanceScorer.Input(
                    searchVolume     = metric?.searchVolume,
                    cpc              = metric?.cpc,
                    competitionIndex = metric?.competitionIndex
                )
            }
        }

        return pool
    }
}
