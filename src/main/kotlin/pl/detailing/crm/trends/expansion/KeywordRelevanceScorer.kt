package pl.detailing.crm.trends.expansion

/**
 * Computes a composite relevance score (0.0–1.0) for each keyword.
 *
 * Score is built from three DataForSEO metrics, each normalized to [0,1]
 * relative to the max value within the current scoring pool:
 *
 *   score = (volume / maxVolume) × 0.50   — reach: how many people search
 *         + (cpc    / maxCpc   ) × 0.30   — commercial intent: what advertisers pay
 *         + (competitionIndex  ) × 0.20   — market demand: how proven the keyword is
 *
 * Relative normalization keeps scores comparable regardless of absolute market size.
 * Keywords with null metrics contribute 0 for that dimension.
 */
object KeywordRelevanceScorer {

    private const val WEIGHT_VOLUME      = 0.50
    private const val WEIGHT_CPC         = 0.30
    private const val WEIGHT_COMPETITION = 0.20

    data class Input(
        val searchVolume: Int?,
        val cpc: Double?,
        val competitionIndex: Int?
    )

    /**
     * Scores all keywords in the pool in one pass.
     * Returns a map of keyword → score, guaranteed to be in [0.0, 1.0].
     */
    fun scoreAll(pool: Map<String, Input>): Map<String, Double> {
        if (pool.isEmpty()) return emptyMap()

        val maxVolume = pool.values.mapNotNull { it.searchVolume }.maxOrNull()?.toDouble() ?: 1.0
        val maxCpc    = pool.values.mapNotNull { it.cpc }.maxOrNull().takeIf { it != null && it > 0.0 } ?: 1.0

        return pool.mapValues { (_, input) ->
            val volumeScore = (input.searchVolume ?: 0).toDouble() / maxVolume * WEIGHT_VOLUME
            val cpcScore    = (input.cpc ?: 0.0) / maxCpc * WEIGHT_CPC
            val compScore   = (input.competitionIndex ?: 0) / 100.0 * WEIGHT_COMPETITION
            (volumeScore + cpcScore + compScore).coerceIn(0.0, 1.0)
        }
    }
}
