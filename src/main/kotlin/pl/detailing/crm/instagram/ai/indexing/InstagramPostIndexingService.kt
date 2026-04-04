package pl.detailing.crm.instagram.ai.indexing

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import pl.detailing.crm.instagram.ai.classification.InstagramPostClassificationService
import pl.detailing.crm.instagram.ai.inspiration.InstagramInspirationService
import pl.detailing.crm.instagram.infrastructure.InstagramPostSnapshotRepository
import pl.detailing.crm.shared.InstagramPostSnapshotId
import pl.detailing.crm.shared.StudioId

/**
 * Serwis indeksujący posty Instagramowe w bazie wektorowej (pgvector).
 *
 * Reaguje asynchronicznie na zdarzenia [InstagramPostReactionChangedEvent]:
 *   - LIKED/DISLIKED → klasyfikuje post LLM-em, indeksuje z metadanymi w VectorStore
 *   - null (usunięcie reakcji) → usuwa wpis z VectorStore
 *
 * Metadane dokumentu w VectorStore (tabela: instagram_post_vectors):
 *   - feedback_status:  "LIKED" | "DISLIKED"
 *   - studio_id:        UUID studia (string) — izolacja per-tenant
 *   - post_tone:        premium | technical | emotional | casual
 *   - post_length:      short | full
 *   - service_type:     ppf | ceramic | detailing | interior | wrap | polish | other
 *   - car_brand:        marka auta lub "universal"
 *   - full_content:     pełna treść posta (zwracana jako przykład few-shot)
 *   - source_post_id:   UUID snapshotu (do deduplikacji / upsert)
 *
 * Tekst embeddingu: skondensowany opis semantyczny z klasyfikatora LLM
 * (embeddingText, nie pełna treść) — lepsza jakość wyszukiwania podobieństwa.
 *
 * @Async zapewnia, że klasyfikacja LLM + embedding nie blokuje wątku HTTP.
 */
@Service
class InstagramPostIndexingService(
    private val postSnapshotRepository: InstagramPostSnapshotRepository,
    private val classificationService: InstagramPostClassificationService,
    private val vectorStore: VectorStore,
    private val jdbcTemplate: JdbcTemplate
) {
    private val logger = LoggerFactory.getLogger(InstagramPostIndexingService::class.java)
    private val ioScope = CoroutineScope(Dispatchers.IO)

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

        // Klasyfikacja przez LLM
        val classification = classificationService.classify(caption)
        val postLength = classificationService.determinePostLength(caption)

        // Upsert: usuń stary wpis przed dodaniem nowego
        removeVectorEntry(studioId, postId)

        val document = Document.builder()
            .text(classification.embeddingText)
            .metadata(
                mapOf(
                    InstagramInspirationService.META_FEEDBACK_STATUS to feedbackStatus,
                    InstagramInspirationService.META_STUDIO_ID       to studioId.value.toString(),
                    InstagramInspirationService.META_POST_TONE       to classification.postTone,
                    InstagramInspirationService.META_POST_LENGTH     to postLength,
                    InstagramInspirationService.META_SERVICE_TYPE    to classification.serviceType,
                    "car_brand"       to classification.carBrand,
                    "full_content"    to caption,
                    "source_post_id"  to postId.value.toString()
                )
            )
            .build()

        vectorStore.add(listOf(document))

        logger.info(
            "Indexed post: studioId={}, postId={}, status={}, tone={}, length={}, service={}, brand='{}'",
            studioId, postId, feedbackStatus,
            classification.postTone, postLength,
            classification.serviceType, classification.carBrand
        )
    }

    // ── Usuwanie ──────────────────────────────────────────────────────────────

    private fun removeVectorEntry(studioId: StudioId, postId: InstagramPostSnapshotId) {
        try {
            val deleted = jdbcTemplate.update(
                """
                DELETE FROM instagram_post_vectors
                WHERE metadata->>'source_post_id' = ?
                  AND metadata->>'studio_id'      = ?
                """.trimIndent(),
                postId.value.toString(),
                studioId.value.toString()
            )
            if (deleted > 0) {
                logger.debug("Removed {} vector entries: studioId={}, postId={}", deleted, studioId, postId)
            }
        } catch (e: Exception) {
            logger.warn("Could not remove vector entry for studioId={}, postId={}: {}", studioId, postId, e.message)
        }
    }
}
