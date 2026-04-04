package com.example.demo.adcopy.service

import com.example.demo.adcopy.model.FallbackInfo
import com.example.demo.adcopy.model.InspirationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.ai.vectorstore.SearchRequest
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.ai.vectorstore.filter.Filter
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder
import org.springframework.stereotype.Service

/**
 * Warstwa Retrieval – pobiera spersonalizowany kontekst (polubione / odrzucone nagłówki)
 * z VectorStore (pgvector) w celu zasilenia promptu Few-Shot.
 */
@Service
class HeadlineInspirationService(
    private val vectorStore: VectorStore
) {
    private val logger = LoggerFactory.getLogger(HeadlineInspirationService::class.java)

    companion object {
        private const val LIKED_TOP_K = 5
        private const val DISLIKED_TOP_K = 3
        private const val MIN_THRESHOLD = 3
    }

    /**
     * Zbiera kontekst inspiracji z warstwowymi fallbackami:
     *   Level 1: LIKE + user + tone + length + service  (ideał)
     *   Level 2: LIKE + user + tone + length            (bez service)
     *   Level 3: LIKE + tone + length (globalnie)       (bez user)
     *   Level 4: LIKE + user                            (bez tone/length)
     *   Level 5: brak przykładów
     */
    suspend fun getInspirationContext(
        topic: String,
        userId: Long,
        postTone: String? = null,
        postLength: String? = null,
        serviceType: String? = null,
        styleNotes: List<String> = emptyList()
    ): InspirationContext = coroutineScope {
        logger.info(
            "Fetching inspiration: topic='{}', userId={}, tone={}, length={}, service={}",
            topic, userId, postTone, postLength, serviceType
        )

        // --- DISLIKED: zawsze per user, bez filtrów tone/length ---
        val dislikedDeferred = async {
            withContext(Dispatchers.IO) {
                similaritySearch(topic, DISLIKED_TOP_K, buildFilter("DISLIKE", userId, null, null, null))
            }
        }

        // --- LIKED: warstwowe fallbacki ---
        val likedDeferred = async {
            withContext(Dispatchers.IO) {
                resolvePositiveExamples(topic, userId, postTone, postLength, serviceType)
            }
        }

        val (positives, fallbackInfo) = likedDeferred.await()
        val negatives = dislikedDeferred.await()

        logger.info(
            "Inspiration ready: {} positive, {} negative, fallback level={}",
            positives.size, negatives.size, fallbackInfo.level
        )

        InspirationContext(
            positiveExamples = positives,
            negativeExamples = negatives,
            requestedTone = postTone,
            requestedLength = postLength,
            fallbackInfo = fallbackInfo,
            styleNotes = styleNotes
        )
    }

    // ──────────────────────────────────────────────────────────────
    // Warstwowe fallbacki dla pozytywnych przykładów
    // ──────────────────────────────────────────────────────────────

    private fun resolvePositiveExamples(
        topic: String,
        userId: Long,
        tone: String?,
        length: String?,
        service: String?
    ): Pair<List<String>, FallbackInfo> {

        val hasToneOrLength = tone != null || length != null

        // Level 1: LIKE + user + tone + length + service
        if (hasToneOrLength && service != null) {
            val results = similaritySearch(topic, LIKED_TOP_K, buildFilter("LIKE", userId, tone, length, service))
            if (results.size >= MIN_THRESHOLD) {
                logger.info("Level 1 (ideal): found {} results", results.size)
                return results to FallbackInfo.ideal()
            }
            logger.info("Level 1: only {} results, trying level 2...", results.size)
        }

        // Level 2: LIKE + user + tone + length (bez service)
        if (hasToneOrLength) {
            val results = similaritySearch(topic, LIKED_TOP_K, buildFilter("LIKE", userId, tone, length, null))
            if (results.size >= MIN_THRESHOLD) {
                logger.info("Level 2 (relax service): found {} results", results.size)
                return results to FallbackInfo.relaxService()
            }
            logger.info("Level 2: only {} results, trying level 3...", results.size)
        }

        // Level 3: LIKE + tone + length globalnie (bez user_id)
        if (hasToneOrLength) {
            val results = similaritySearch(topic, LIKED_TOP_K, buildFilter("LIKE", null, tone, length, null))
            if (results.size >= MIN_THRESHOLD) {
                logger.info("Level 3 (global tone): found {} results", results.size)
                return results to FallbackInfo.globalTone()
            }
            logger.info("Level 3: only {} results, trying level 4...", results.size)
        }

        // Level 4: LIKE + user (ogólne preferencje)
        val results = similaritySearch(topic, LIKED_TOP_K, buildFilter("LIKE", userId, null, null, null))
        if (results.isNotEmpty()) {
            logger.info("Level 4 (user only): found {} results", results.size)
            return results to FallbackInfo.userOnly()
        }

        // Level 5: brak przykładów
        logger.info("Level 5: no positive examples found at all")
        return emptyList<String>() to FallbackInfo.empty()
    }

    // ──────────────────────────────────────────────────────────────
    // Budowanie filtrów i wyszukiwanie
    // ──────────────────────────────────────────────────────────────

    private fun buildFilter(
        feedbackStatus: String,
        userId: Long?,
        tone: String?,
        length: String?,
        service: String?
    ): Filter.Expression {
        val b = FilterExpressionBuilder()

        var expr = b.eq("feedback_status", feedbackStatus)

        if (userId != null) {
            expr = b.and(expr, b.eq("user_id", userId.toString()))
        }
        if (tone != null) {
            expr = b.and(expr, b.eq("post_tone", tone))
        }
        if (length != null) {
            expr = b.and(expr, b.eq("post_length", length))
        }
        if (service != null) {
            expr = b.and(expr, b.eq("service_type", service))
        }

        return expr.build()
    }

    private fun similaritySearch(
        topic: String,
        topK: Int,
        filterExpression: Filter.Expression
    ): List<String> {
        val request = SearchRequest.builder()
            .query(topic)
            .topK(topK)
            .filterExpression(filterExpression)
            .build()

        val results = vectorStore.similaritySearch(request)

        results?.forEachIndexed { index, doc ->
            logger.debug(
                "  [{}] text='{}', metadata={}",
                index, doc.text?.take(80), doc.metadata
            )
        }

        val texts = results?.mapNotNull { it.text } ?: emptyList()
        logger.debug("Similarity search returned {} results for filter {}", texts.size, filterExpression)
        return texts
    }
}
