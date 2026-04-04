package pl.detailing.crm.instagram.ai.inspiration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.instagram.ai.infrastructure.InstagramPostVectorStore
import pl.detailing.crm.instagram.ai.infrastructure.OpenAiClient
import pl.detailing.crm.instagram.ai.model.FallbackInfo
import pl.detailing.crm.instagram.ai.model.InstagramInspirationContext
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
 * Przykłady negatywne (DISLIKED) są zawsze per-studio, bez filtrów ton/długość —
 * dzięki temu system uczy się unikać stylu odrzuconego przez konkretne studio.
 *
 * Zapytanie do pgvector jest embeddingiem [topic] (wektorem semantycznym tematu),
 * co pozwala znaleźć posty tematycznie podobne do generowanego.
 */
@Service
class InstagramInspirationService(
    private val vectorStore: InstagramPostVectorStore,
    private val openAiClient: OpenAiClient
) {
    private val logger = LoggerFactory.getLogger(InstagramInspirationService::class.java)

    companion object {
        private const val LIKED_TOP_K = 5
        private const val DISLIKED_TOP_K = 3
        private const val MIN_RESULTS_THRESHOLD = 3

        const val META_FEEDBACK_STATUS = "feedback_status"
        const val META_STUDIO_ID = "studio_id"
        const val META_POST_TONE = "post_tone"
        const val META_POST_LENGTH = "post_length"
        const val META_SERVICE_TYPE = "service_type"
    }

    /**
     * Zbiera kontekst inspiracji dla danego studia.
     *
     * Embedding [topic] jest obliczany raz i używany równolegle dla obu zapytań.
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

        // Embedding tematu — obliczany raz, używany przez oba zapytania
        val queryEmbedding = withContext(Dispatchers.IO) { openAiClient.embedding(topic) }

        // Przykłady negatywne: per-studio, bez filtrów ton/długość
        val dislikedDeferred = async(Dispatchers.IO) {
            vectorStore.similaritySearch(
                queryEmbedding = queryEmbedding,
                topK = DISLIKED_TOP_K,
                metadataFilter = buildFilter(
                    feedbackStatus = InstagramPostReaction.DISLIKED.name,
                    studioId = studioId,
                    tone = null, length = null, service = null
                )
            )
        }

        // Przykłady pozytywne: warstwowe fallbacki
        val likedDeferred = async(Dispatchers.IO) {
            resolvePositiveExamples(queryEmbedding, studioId, postTone, postLength, serviceType)
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

    // ── Warstwowe fallbacki dla pozytywnych przykładów ────────────────────────

    private fun resolvePositiveExamples(
        queryEmbedding: FloatArray,
        studioId: StudioId,
        tone: String?,
        length: String?,
        service: String?
    ): Pair<List<String>, FallbackInfo> {

        val hasToneOrLength = tone != null || length != null

        // Level 1: LIKED + studio + ton + długość + usługa (ideał)
        if (hasToneOrLength && service != null) {
            val results = search(queryEmbedding, LIKED_TOP_K,
                buildFilter(InstagramPostReaction.LIKED.name, studioId, tone, length, service))
            if (results.size >= MIN_RESULTS_THRESHOLD) {
                logger.debug("Level 1 (ideal): {} results", results.size)
                return results to FallbackInfo.ideal()
            }
            logger.debug("Level 1: {} results, trying level 2...", results.size)
        }

        // Level 2: LIKED + studio + ton + długość (bez usługi)
        if (hasToneOrLength) {
            val results = search(queryEmbedding, LIKED_TOP_K,
                buildFilter(InstagramPostReaction.LIKED.name, studioId, tone, length, null))
            if (results.size >= MIN_RESULTS_THRESHOLD) {
                logger.debug("Level 2 (relax service): {} results", results.size)
                return results to FallbackInfo.relaxService()
            }
            logger.debug("Level 2: {} results, trying level 3...", results.size)
        }

        // Level 3: LIKED + ton + długość globalnie (bez filtra studia)
        if (hasToneOrLength) {
            val results = search(queryEmbedding, LIKED_TOP_K,
                buildFilter(InstagramPostReaction.LIKED.name, null, tone, length, null))
            if (results.size >= MIN_RESULTS_THRESHOLD) {
                logger.debug("Level 3 (global tone): {} results", results.size)
                return results to FallbackInfo.globalTone()
            }
            logger.debug("Level 3: {} results, trying level 4...", results.size)
        }

        // Level 4: LIKED + studio (ogólne preferencje)
        val results = search(queryEmbedding, LIKED_TOP_K,
            buildFilter(InstagramPostReaction.LIKED.name, studioId, null, null, null))
        if (results.isNotEmpty()) {
            logger.debug("Level 4 (studio only): {} results", results.size)
            return results to FallbackInfo.studioOnly()
        }

        // Level 5: brak przykładów
        logger.info("Level 5: no positive examples found for studioId={}", studioId)
        return emptyList<String>() to FallbackInfo.empty()
    }

    // ── Budowanie filtrów i wyszukiwanie ──────────────────────────────────────

    private fun buildFilter(
        feedbackStatus: String,
        studioId: StudioId?,
        tone: String?,
        length: String?,
        service: String?
    ): Map<String, String?> = buildMap {
        put(META_FEEDBACK_STATUS, feedbackStatus)
        put(META_STUDIO_ID, studioId?.value?.toString())
        put(META_POST_TONE, tone)
        put(META_POST_LENGTH, length)
        put(META_SERVICE_TYPE, service)
    }

    private fun search(
        queryEmbedding: FloatArray,
        topK: Int,
        filter: Map<String, String?>
    ): List<String> = vectorStore.similaritySearch(queryEmbedding, topK, filter)
}
