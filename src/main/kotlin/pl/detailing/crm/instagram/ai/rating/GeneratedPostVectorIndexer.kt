package pl.detailing.crm.instagram.ai.rating

import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import pl.detailing.crm.instagram.ai.classification.InstagramPostClassificationService
import pl.detailing.crm.instagram.ai.inspiration.InstagramInspirationService
import pl.detailing.crm.instagram.ai.model.GeneratedPostRating
import pl.detailing.crm.shared.StudioId
import java.util.UUID

/**
 * Indeksuje OCENIONE posty własne studia w bazie wektorowej — tym samym potokiem
 * klasyfikacji, którym idą posty konkurencji
 * (patrz [pl.detailing.crm.instagram.ai.indexing.InstagramPostIndexingService]).
 *
 * Dzięki wspólnym kluczom metadanych (feedback_status LIKED/DISLIKED) retrieval few-shot
 * widzi własne posty bez żadnej zmiany zapytań; odróżnia je metadana `source="generated"`,
 * a przy ocenie negatywnej dochodzi `rating_comment` — powód odrzucenia podany przez studio.
 *
 * Zmiana oceny to usunięcie starego wpisu i dodanie nowego: bez tego post żyłby
 * w VectorStore jednocześnie jako wzorzec i jako antywzorzec.
 */
@Service
class GeneratedPostVectorIndexer(
    private val classificationService: InstagramPostClassificationService,
    private val vectorStore: VectorStore,
    private val jdbcTemplate: JdbcTemplate
) {
    private val logger = LoggerFactory.getLogger(GeneratedPostVectorIndexer::class.java)

    suspend fun index(
        studioId: StudioId,
        postId: UUID,
        content: String,
        rating: GeneratedPostRating,
        ratingComment: String?
    ) {
        val classification = classificationService.classify(content)
        val postLength = classificationService.determinePostLength(content)

        // Upsert: przy ponownej ocenie stary wpis musi zniknąć przed dodaniem nowego.
        remove(studioId, postId)

        val metadata = mutableMapOf<String, Any>(
            InstagramInspirationService.META_FEEDBACK_STATUS to rating.toFeedbackStatus(),
            InstagramInspirationService.META_STUDIO_ID to studioId.value.toString(),
            InstagramInspirationService.META_POST_TONE to classification.postTone,
            InstagramInspirationService.META_POST_LENGTH to postLength,
            InstagramInspirationService.META_SERVICE_TYPE to classification.serviceType,
            InstagramInspirationService.META_SOURCE to InstagramInspirationService.SOURCE_GENERATED,
            "car_brand" to classification.carBrand,
            "full_content" to content,
            "source_post_id" to postId.toString()
        )
        if (rating == GeneratedPostRating.NEGATIVE && !ratingComment.isNullOrBlank()) {
            metadata[InstagramInspirationService.META_RATING_COMMENT] = ratingComment
        }

        vectorStore.add(
            listOf(
                Document.builder()
                    .text(classification.embeddingText)
                    .metadata(metadata)
                    .build()
            )
        )

        logger.info(
            "Indexed generated post: studioId={}, postId={}, rating={}, tone={}, service={}",
            studioId, postId, rating, classification.postTone, classification.serviceType
        )
    }

    fun remove(studioId: StudioId, postId: UUID) {
        try {
            val deleted = jdbcTemplate.update(
                """
                DELETE FROM instagram_post_vectors
                WHERE metadata->>'source_post_id' = ?
                  AND metadata->>'studio_id'      = ?
                """.trimIndent(),
                postId.toString(),
                studioId.value.toString()
            )
            if (deleted > 0) {
                logger.debug("Removed {} vector entries for generated post {}", deleted, postId)
            }
        } catch (e: Exception) {
            logger.warn("Could not remove vector entry for generated post {}: {}", postId, e.message)
        }
    }

    /**
     * Ocena własnego posta jest tym samym sygnałem, co reakcja na post konkurencji,
     * więc mapuje się na te same statusy — retrieval nie musi znać dwóch słowników.
     */
    private fun GeneratedPostRating.toFeedbackStatus(): String =
        if (this == GeneratedPostRating.POSITIVE) "LIKED" else "DISLIKED"
}
