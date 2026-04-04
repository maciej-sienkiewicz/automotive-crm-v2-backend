package pl.detailing.crm.instagram.ai.inspiration

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
import pl.detailing.crm.instagram.ai.model.FallbackInfo
import pl.detailing.crm.instagram.ai.model.InstagramInspirationContext
import pl.detailing.crm.shared.InstagramPostReaction
import pl.detailing.crm.shared.StudioId

/**
 * Warstwa Retrieval – pobiera spersonalizowany kontekst few-shot ze sklepu wektorowego (pgvector).
 *
 * Strategia warstwowego fallbacku dla przykładów pozytywnych (LIKED):
 *   Level 1 – ideał:         LIKED + studio + ton + długość + usługa
 *   Level 2 – relaks usługi: LIKED + studio + ton + długość (bez filtra usługi)
 *   Level 3 – globalny ton:  LIKED + ton + długość (bez filtra studia)
 *   Level 4 – tylko studio:  LIKED + studio (bez ton/długość)
 *   Level 5 – brak przykładów (LLM generuje bez few-shot)
 *
 * Przykłady negatywne (DISLIKED) są zawsze per-studio, bez filtrów ton/długość —
 * dzięki temu system uczy się unikać stylu odrzuconego przez konkretne studio.
 *
 * Klucze metadanych w VectorStore: feedback_status, studio_id, post_tone,
 *   post_length, service_type, car_brand, full_content, source_post_id.
 */
@Service
class InstagramInspirationService(
    private val vectorStore: VectorStore
) {
    private val logger = LoggerFactory.getLogger(InstagramInspirationService::class.java)

    companion object {
        private const val LIKED_TOP_K = 5
        private const val DISLIKED_TOP_K = 3
        private const val MIN_RESULTS_THRESHOLD = 3

        // Klucze metadanych w VectorStore
        const val META_FEEDBACK_STATUS = "feedback_status"
        const val META_STUDIO_ID = "studio_id"
        const val META_POST_TONE = "post_tone"
        const val META_POST_LENGTH = "post_length"
        const val META_SERVICE_TYPE = "service_type"
    }

    /**
     * Zbiera kontekst inspiracji dla danego studia.
     *
     * @param topic       Temat generowanego posta (użyty jako wektor zapytania)
     * @param studioId    Identyfikator studia (filtrowanie per-tenant)
     * @param postTone    Preferowany ton posta (opcjonalny)
     * @param postLength  Preferowana długość posta (opcjonalny)
     * @param serviceType Rodzaj usługi (opcjonalny)
     * @param styleNotes  Reguły stylistyczne (nadrzędne wobec przykładów)
     */
    suspend fun getInspirationContext(
        topic: String,
        studioId: StudioId,
        postTone: String? = null,
        postLength: String? = null,
        serviceType: String? = null,
        styleNotes: List<String> = emptyList()
    ): InstagramInspirationContext = coroutineScope {
        logger.info(
            "Fetching inspiration: topic='{}', studioId={}, tone={}, length={}, service={}",
            topic, studioId, postTone, postLength, serviceType
        )

        // Przykłady negatywne: zawsze per-studio, bez filtrów ton/długość
        val dislikedDeferred = async {
            withContext(Dispatchers.IO) {
                similaritySearch(
                    query = topic,
                    topK = DISLIKED_TOP_K,
                    filter = buildFilter(
                        feedbackStatus = InstagramPostReaction.DISLIKED.name,
                        studioId = studioId,
                        tone = null,
                        length = null,
                        service = null
                    )
                )
            }
        }

        // Przykłady pozytywne: warstwowe fallbacki
        val likedDeferred = async {
            withContext(Dispatchers.IO) {
                resolvePositiveExamples(topic, studioId, postTone, postLength, serviceType)
            }
        }

        val (positives, fallbackInfo) = likedDeferred.await()
        val negatives = dislikedDeferred.await()

        logger.info(
            "Inspiration ready: {} positive, {} negative, fallback level={}",
            positives.size, negatives.size, fallbackInfo.level
        )

        InstagramInspirationContext(
            positiveExamples = positives,
            negativeExamples = negatives,
            requestedTone = postTone,
            requestedLength = postLength,
            fallbackInfo = fallbackInfo,
            styleNotes = styleNotes
        )
    }

    // ── Warstwowe fallbacki dla pozytywnych przykładów ─────────────────────────

    private fun resolvePositiveExamples(
        topic: String,
        studioId: StudioId,
        tone: String?,
        length: String?,
        service: String?
    ): Pair<List<String>, FallbackInfo> {

        val hasToneOrLength = tone != null || length != null

        // Level 1: LIKED + studio + ton + długość + usługa (ideał)
        if (hasToneOrLength && service != null) {
            val results = similaritySearch(
                query = topic,
                topK = LIKED_TOP_K,
                filter = buildFilter(InstagramPostReaction.LIKED.name, studioId, tone, length, service)
            )
            if (results.size >= MIN_RESULTS_THRESHOLD) {
                logger.debug("Level 1 (ideal): found {} results", results.size)
                return results to FallbackInfo.ideal()
            }
            logger.debug("Level 1: only {} results, trying level 2...", results.size)
        }

        // Level 2: LIKED + studio + ton + długość (bez usługi)
        if (hasToneOrLength) {
            val results = similaritySearch(
                query = topic,
                topK = LIKED_TOP_K,
                filter = buildFilter(InstagramPostReaction.LIKED.name, studioId, tone, length, null)
            )
            if (results.size >= MIN_RESULTS_THRESHOLD) {
                logger.debug("Level 2 (relax service): found {} results", results.size)
                return results to FallbackInfo.relaxService()
            }
            logger.debug("Level 2: only {} results, trying level 3...", results.size)
        }

        // Level 3: LIKED + ton + długość globalnie (bez filtra studia)
        if (hasToneOrLength) {
            val results = similaritySearch(
                query = topic,
                topK = LIKED_TOP_K,
                filter = buildFilter(InstagramPostReaction.LIKED.name, null, tone, length, null)
            )
            if (results.size >= MIN_RESULTS_THRESHOLD) {
                logger.debug("Level 3 (global tone): found {} results", results.size)
                return results to FallbackInfo.globalTone()
            }
            logger.debug("Level 3: only {} results, trying level 4...", results.size)
        }

        // Level 4: LIKED + studio (ogólne preferencje, bez ton/długość)
        val results = similaritySearch(
            query = topic,
            topK = LIKED_TOP_K,
            filter = buildFilter(InstagramPostReaction.LIKED.name, studioId, null, null, null)
        )
        if (results.isNotEmpty()) {
            logger.debug("Level 4 (studio only): found {} results", results.size)
            return results to FallbackInfo.studioOnly()
        }

        // Level 5: brak przykładów
        logger.info("Level 5: no positive examples found for studioId={}", studioId)
        return emptyList<String>() to FallbackInfo.empty()
    }

    // ── Budowanie filtrów i wyszukiwanie ────────────────────────────────────────

    private fun buildFilter(
        feedbackStatus: String,
        studioId: StudioId?,
        tone: String?,
        length: String?,
        service: String?
    ): Filter.Expression {
        val b = FilterExpressionBuilder()

        var expr = b.eq(META_FEEDBACK_STATUS, feedbackStatus)

        if (studioId != null) {
            expr = b.and(expr, b.eq(META_STUDIO_ID, studioId.value.toString()))
        }
        if (tone != null) {
            expr = b.and(expr, b.eq(META_POST_TONE, tone))
        }
        if (length != null) {
            expr = b.and(expr, b.eq(META_POST_LENGTH, length))
        }
        if (service != null) {
            expr = b.and(expr, b.eq(META_SERVICE_TYPE, service))
        }

        return expr.build()
    }

    private fun similaritySearch(
        query: String,
        topK: Int,
        filter: Filter.Expression
    ): List<String> {
        val request = SearchRequest.builder()
            .query(query)
            .topK(topK)
            .filterExpression(filter)
            .build()

        val results = vectorStore.similaritySearch(request) ?: emptyList()

        results.forEachIndexed { index, doc ->
            logger.debug(
                "  [{}] text='{}', metadata={}",
                index, doc.text?.take(80), doc.metadata
            )
        }

        return results.mapNotNull { it.text }
            .also { logger.debug("Similarity search: {} results, filter={}", it.size, filter) }
    }
}
