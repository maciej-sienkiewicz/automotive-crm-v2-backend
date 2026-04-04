package pl.detailing.crm.instagram.ai.indexing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import pl.detailing.crm.instagram.ai.classification.InstagramPostClassificationService
import pl.detailing.crm.instagram.ai.infrastructure.InstagramPostVectorStore
import pl.detailing.crm.instagram.ai.infrastructure.OpenAiClient
import pl.detailing.crm.instagram.ai.inspiration.InstagramInspirationService
import pl.detailing.crm.instagram.infrastructure.InstagramPostSnapshotRepository
import pl.detailing.crm.shared.InstagramPostSnapshotId
import pl.detailing.crm.shared.StudioId
import java.util.UUID

/**
 * Serwis indeksujący posty Instagramowe w bazie wektorowej (pgvector).
 *
 * Reaguje asynchronicznie na zdarzenia [InstagramPostReactionChangedEvent]:
 *   - LIKED/DISLIKED → klasyfikuje post LLM-em, oblicza embedding, zapisuje w VectorStore
 *   - null (usunięcie reakcji) → usuwa wpis z VectorStore
 *
 * Metadane dokumentu zapisywane w tabeli `instagram_post_vectors`:
 *   - feedback_status:  "LIKED" | "DISLIKED"
 *   - studio_id:        UUID studia (string)
 *   - post_tone:        premium | technical | emotional | casual
 *   - post_length:      short | full
 *   - service_type:     ppf | ceramic | detailing | interior | wrap | polish | other
 *   - car_brand:        marka auta lub "universal"
 *   - full_content:     pełna treść posta (zwracana w wynikach few-shot)
 *   - source_post_id:   UUID snapshotu posta (do deduplikacji)
 *
 * Tekst embeddingu: skondensowany opis semantyczny (embeddingText z klasyfikatora).
 * Wynik wyszukiwania: full_content (pełna treść) — lepszy wzorzec stylistyczny dla LLM.
 */
@Service
class InstagramPostIndexingService(
    private val postSnapshotRepository: InstagramPostSnapshotRepository,
    private val classificationService: InstagramPostClassificationService,
    private val openAiClient: OpenAiClient,
    private val vectorStore: InstagramPostVectorStore
) {
    private val logger = LoggerFactory.getLogger(InstagramPostIndexingService::class.java)
    private val ioScope = CoroutineScope(Dispatchers.IO)

    /**
     * Nasłuchuje zdarzeń zmiany reakcji i asynchronicznie aktualizuje VectorStore.
     *
     * @Async zapewnia, że indeksowanie nie blokuje wątku HTTP —
     * klasyfikacja LLM + obliczenie embeddingu trwa kilka sekund
     * i nie może opóźniać odpowiedzi na żądanie reakcji.
     */
    @Async
    @EventListener
    fun onReactionChanged(event: InstagramPostReactionChangedEvent) {
        logger.info(
            "Reaction changed: studioId={}, postId={}, reaction={}",
            event.studioId, event.postId, event.reaction
        )

        ioScope.launch {
            try {
                if (event.reaction == null) {
                    removeVectorEntry(event.studioId, event.postId)
                } else {
                    indexPost(event.studioId, event.postId, event.reaction.name)
                }
            } catch (e: Exception) {
                logger.error(
                    "Failed to update VectorStore for studioId={}, postId={}: {}",
                    event.studioId, event.postId, e.message, e
                )
            }
        }
    }

    // ── Indeksowanie ──────────────────────────────────────────────────────────

    private suspend fun indexPost(
        studioId: StudioId,
        postId: InstagramPostSnapshotId,
        feedbackStatus: String
    ) {
        val snapshot = postSnapshotRepository.findById(postId.value).orElse(null)
        if (snapshot == null) {
            logger.warn("Post snapshot not found for id={}, skipping indexing", postId)
            return
        }

        val caption = snapshot.caption
        if (caption.isNullOrBlank()) {
            logger.info("Post {} has no caption, skipping indexing", postId)
            return
        }

        // 1. Klasyfikacja przez LLM
        val classification = classificationService.classify(caption)
        val postLength = classificationService.determinePostLength(caption)

        // 2. Oblicz embedding skondensowanego tekstu
        val embedding = openAiClient.embedding(classification.embeddingText)

        // 3. Zapisz w VectorStore (upsert: usuwamy stary wpis przed nowym)
        removeVectorEntry(studioId, postId)

        vectorStore.add(
            id = UUID.randomUUID(),
            embeddingText = classification.embeddingText,
            embedding = embedding,
            metadata = buildMap {
                put(InstagramInspirationService.META_FEEDBACK_STATUS, feedbackStatus)
                put(InstagramInspirationService.META_STUDIO_ID, studioId.value.toString())
                put(InstagramInspirationService.META_POST_TONE, classification.postTone)
                put(InstagramInspirationService.META_POST_LENGTH, postLength)
                put(InstagramInspirationService.META_SERVICE_TYPE, classification.serviceType)
                put("car_brand", classification.carBrand)
                put("full_content", caption)
                put("source_post_id", postId.value.toString())
            }
        )

        logger.info(
            "Indexed post: studioId={}, postId={}, status={}, tone={}, length={}, service={}, brand='{}'",
            studioId, postId, feedbackStatus,
            classification.postTone, postLength,
            classification.serviceType, classification.carBrand
        )
    }

    // ── Usuwanie ──────────────────────────────────────────────────────────────

    private fun removeVectorEntry(studioId: StudioId, postId: InstagramPostSnapshotId) {
        val deleted = vectorStore.delete(
            mapOf(
                "source_post_id" to postId.value.toString(),
                "studio_id" to studioId.value.toString()
            )
        )
        if (deleted > 0) {
            logger.debug("Removed {} vector entries: studioId={}, postId={}", deleted, studioId, postId)
        }
    }
}
