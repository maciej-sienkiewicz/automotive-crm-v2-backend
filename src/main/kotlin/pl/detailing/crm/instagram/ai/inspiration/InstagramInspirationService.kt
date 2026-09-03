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
import org.springframework.ai.document.Document
import org.springframework.stereotype.Service
import pl.detailing.crm.instagram.ai.model.FallbackInfo
import pl.detailing.crm.instagram.ai.model.InstagramInspirationContext
import pl.detailing.crm.instagram.ai.model.OwnRejectionExample
import pl.detailing.crm.shared.InstagramPostReaction
import pl.detailing.crm.shared.StudioId

/**
 * Warstwa Retrieval – pobiera spersonalizowany kontekst few-shot z bazy wektorowej (pgvector).
 *
 * Strategia warstwowego fallbacku dla przykładów pozytywnych (LIKED):
 *   Level 1 – ideał:         LIKED + studio + ton + długość + usługa
 *   Level 2 – relaks usługi: LIKED + studio + ton + długość (bez filtra usługi)
 *   Level 3 – globalny ton:  LIKED + ton + długość (bez filtra studia)
 *   Level 4 – tylko studio:  LIKED + studio (bez ton/długość)
 *   Level 5 – brak przykładów (LLM generuje bez few-shot)
 *
 * Przykłady negatywne (DISLIKED) są zawsze per-studio, bez filtrów ton/długość.
 *
 * Klucze metadanych w VectorStore: feedback_status, studio_id, post_tone,
 *   post_length, service_type, car_brand, full_content, source_post_id,
 *   source ("generated" dla własnych postów studia) i rating_comment (powód odrzucenia).
 *
 * Własne, ocenione posty studia są traktowane inaczej niż posty konkurencji:
 *   - POZYTYWNE trafiają na początek POSITIVE_EXAMPLES (własny zaakceptowany post
 *     jest mocniejszą kotwicą stylu niż cudzy polubiony),
 *   - NEGATYWNE trafiają do osobnej sekcji promptu razem z komentarzem „dlaczego".
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

        /** Własne posty studia — mocniejsza kotwica stylu, więc mają własny, mniejszy budżet. */
        private const val OWN_LIKED_TOP_K = 3
        private const val OWN_REJECTIONS_TOP_K = 3

        const val META_FEEDBACK_STATUS = "feedback_status"
        const val META_STUDIO_ID = "studio_id"
        const val META_POST_TONE = "post_tone"
        const val META_POST_LENGTH = "post_length"
        const val META_SERVICE_TYPE = "service_type"
        const val META_SOURCE = "source"
        const val META_RATING_COMMENT = "rating_comment"
        const val META_FULL_CONTENT = "full_content"

        /** Wartość [META_SOURCE] dla postów wygenerowanych i ocenionych przez samo studio. */
        const val SOURCE_GENERATED = "generated"
    }

    /**
     * Zbiera kontekst inspiracji dla danego studia.
     *
     * @param topic       Temat generowanego posta (wektor zapytania)
     * @param studioId    Identyfikator studia (filtrowanie per-tenant)
     * @param postTone    Preferowany ton posta (opcjonalny)
     * @param postLength  Preferowana długość posta (opcjonalny)
     * @param styleNotes  Reguły stylistyczne (nadrzędne wobec przykładów)
     */
    suspend fun getInspirationContext(
        topic: String,
        studioId: StudioId,
        postTone: String? = null,
        postLength: String? = null,
        styleNotes: List<String> = emptyList()
    ): InstagramInspirationContext = coroutineScope {
        logger.info(
            "Fetching inspiration: topic='{}', studioId={}, tone={}, length={}",
            topic, studioId, postTone, postLength
        )

        // Przykłady negatywne: per-studio, bez filtrów ton/długość
        val dislikedDeferred = async {
            withContext(Dispatchers.IO) {
                similaritySearch(
                    topic, DISLIKED_TOP_K,
                    buildFilter(InstagramPostReaction.DISLIKED.name, studioId, null, null, null)
                )
            }
        }

        // Własne posty studia ocenione POZYTYWNIE — pierwszeństwo w POSITIVE_EXAMPLES.
        val ownPositivesDeferred = async {
            withContext(Dispatchers.IO) {
                searchDocuments(
                    topic, OWN_LIKED_TOP_K,
                    buildFilter(InstagramPostReaction.LIKED.name, studioId, null, null, null, SOURCE_GENERATED)
                ).mapNotNull { it.metadata[META_FULL_CONTENT] as? String }
            }
        }

        // Własne posty ODRZUCONE — razem z komentarzem „dlaczego" (osobna sekcja promptu).
        val ownRejectionsDeferred = async {
            withContext(Dispatchers.IO) {
                searchDocuments(
                    topic, OWN_REJECTIONS_TOP_K,
                    buildFilter(InstagramPostReaction.DISLIKED.name, studioId, null, null, null, SOURCE_GENERATED)
                ).mapNotNull { doc ->
                    val content = doc.metadata[META_FULL_CONTENT] as? String ?: return@mapNotNull null
                    OwnRejectionExample(content, doc.metadata[META_RATING_COMMENT] as? String)
                }
            }
        }

        // Przykłady pozytywne: warstwowe fallbacki
        val likedDeferred = async {
            withContext(Dispatchers.IO) {
                resolvePositiveExamples(topic, studioId, postTone, postLength)
            }
        }

        val (competitorPositives, fallbackInfo) = likedDeferred.await()
        val negatives = dislikedDeferred.await()
        val ownPositives = ownPositivesDeferred.await()
        val ownRejections = ownRejectionsDeferred.await()

        // Własny zaakceptowany post jest mocniejszą kotwicą stylu niż polubiony post
        // konkurencji, więc idzie na początek listy — i wypiera cudzy przykład,
        // a nie dokłada się do niego (budżet promptu jest stały).
        val positives = (ownPositives + competitorPositives).distinct().take(LIKED_TOP_K)

        logger.info(
            "Inspiration ready: {} positive ({} own), {} negative, {} own rejections, fallback level={}",
            positives.size, ownPositives.size, negatives.size, ownRejections.size, fallbackInfo.level
        )

        InstagramInspirationContext(
            positiveExamples = positives,
            negativeExamples = negatives,
            requestedTone = postTone,
            requestedLength = postLength,
            fallbackInfo = fallbackInfo,
            styleNotes = styleNotes,
            ownRejections = ownRejections
        )
    }

    // ── Warstwowe fallbacki dla pozytywnych przykładów ────────────────────────

    private fun resolvePositiveExamples(
        topic: String,
        studioId: StudioId,
        tone: String?,
        length: String?
    ): Pair<List<String>, FallbackInfo> {

        val hasToneOrLength = tone != null || length != null

        // Level 1: LIKED + studio + ton + długość (ideał)
        if (hasToneOrLength) {
            val results = similaritySearch(topic, LIKED_TOP_K,
                buildFilter(InstagramPostReaction.LIKED.name, studioId, tone, length, null))
            if (results.size >= MIN_RESULTS_THRESHOLD) {
                logger.debug("Level 1 (ideal): {} results", results.size)
                return results to FallbackInfo.ideal()
            }
            logger.debug("Level 1: {} results, trying level 2...", results.size)
        }

        // Level 2: LIKED + ton + długość globalnie (bez filtra studia)
        if (hasToneOrLength) {
            val results = similaritySearch(topic, LIKED_TOP_K,
                buildFilter(InstagramPostReaction.LIKED.name, null, tone, length, null))
            if (results.size >= MIN_RESULTS_THRESHOLD) {
                logger.debug("Level 2 (global tone): {} results", results.size)
                return results to FallbackInfo.globalTone()
            }
            logger.debug("Level 2: {} results, trying level 3...", results.size)
        }

        // Level 3: LIKED + studio (ogólne preferencje)
        val results = similaritySearch(topic, LIKED_TOP_K,
            buildFilter(InstagramPostReaction.LIKED.name, studioId, null, null, null))
        if (results.isNotEmpty()) {
            logger.debug("Level 3 (studio only): {} results", results.size)
            return results to FallbackInfo.studioOnly()
        }

        // Level 4: brak przykładów
        logger.info("Level 4: no positive examples found for studioId={}", studioId)
        return emptyList<String>() to FallbackInfo.empty()
    }

    // ── Budowanie filtrów i wyszukiwanie ──────────────────────────────────────

    private fun buildFilter(
        feedbackStatus: String,
        studioId: StudioId?,
        tone: String?,
        length: String?,
        service: String?,
        source: String? = null
    ): Filter.Expression {
        val b = FilterExpressionBuilder()

        var expr = b.eq(META_FEEDBACK_STATUS, feedbackStatus)

        if (studioId != null) expr = b.and(expr, b.eq(META_STUDIO_ID, studioId.value.toString()))
        if (tone != null)     expr = b.and(expr, b.eq(META_POST_TONE, tone))
        if (length != null)   expr = b.and(expr, b.eq(META_POST_LENGTH, length))
        if (service != null)  expr = b.and(expr, b.eq(META_SERVICE_TYPE, service))
        if (source != null)   expr = b.and(expr, b.eq(META_SOURCE, source))

        return expr.build()
    }

    /** Zwraca całe dokumenty — potrzebne wszędzie tam, gdzie liczy się więcej niż `full_content`. */
    private fun searchDocuments(
        query: String,
        topK: Int,
        filter: Filter.Expression
    ): List<Document> {
        val request = SearchRequest.builder()
            .query(query)
            .topK(topK)
            .filterExpression(filter)
            .build()

        val results = vectorStore.similaritySearch(request) ?: emptyList()

        results.forEachIndexed { i, doc ->
            logger.debug("  [{}] text='{}', metadata={}", i, doc.text?.take(80), doc.metadata)
        }

        return results.also { logger.debug("Search returned {} results", it.size) }
    }

    /** Zwraca full_content z metadanych — pełna treść posta (lepszy wzorzec dla few-shot). */
    private fun similaritySearch(
        query: String,
        topK: Int,
        filter: Filter.Expression
    ): List<String> =
        searchDocuments(query, topK, filter).mapNotNull { it.metadata[META_FULL_CONTENT] as? String }
}
