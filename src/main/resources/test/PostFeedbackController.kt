package com.example.demo.adcopy.controller

import com.example.demo.adcopy.model.PostFeedbackRequest
import com.example.demo.adcopy.model.PostFeedbackResponse
import com.example.demo.adcopy.service.PostClassificationService
import org.slf4j.LoggerFactory
import org.springframework.ai.document.Document
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/posts")
class PostFeedbackController(
    private val jdbcTemplate: JdbcTemplate,
    private val vectorStore: VectorStore,
    private val classificationService: PostClassificationService
) {
    private val logger = LoggerFactory.getLogger(PostFeedbackController::class.java)

    data class CreatePostRequest(
        val text: String,
        val userId: Long
    )

    /**
     * POST /api/posts/{id}/feedback
     *
     * Przyjmuje ID posta i LIKE/DISLIKE.
     * 1. Pobiera treść posta z ad_headlines
     * 2. Wysyła do LLM po klasyfikację (postTone, serviceType, carBrand, embeddingText)
     * 3. Oblicza postLength w kodzie
     * 4. Aktualizuje feedback_status w ad_headlines
     * 5. Indeksuje w VectorStore z pełnymi metadanymi
     */
    @PostMapping("/{id}/feedback")
    suspend fun submitFeedback(
        @PathVariable id: Long,
        @RequestBody request: PostFeedbackRequest
    ): ResponseEntity<PostFeedbackResponse> {

        require(request.feedbackStatus in listOf("LIKE", "DISLIKE")) {
            "feedbackStatus musi być LIKE lub DISLIKE"
        }
        require(request.userId > 0) { "userId musi być większe od 0" }

        logger.info("Feedback received: postId={}, status={}, userId={}", id, request.feedbackStatus, request.userId)

        // 1. Pobierz treść posta z bazy
        val postContent = jdbcTemplate.queryForObject(
            "SELECT headline_text FROM ad_headlines WHERE id = ?",
            String::class.java,
            id
        ) ?: throw IllegalArgumentException("Post o id=$id nie istnieje")

        logger.info("Post content ({} chars): '{}'", postContent.length, postContent.take(80))

        // 2. Wyślij do LLM po klasyfikację
        val classification = classificationService.classify(postContent)

        // 3. Oblicz postLength w kodzie
        val postLength = classificationService.determinePostLength(postContent)

        // 4. Zaktualizuj feedback_status w ad_headlines
        val updated = jdbcTemplate.update(
            "UPDATE ad_headlines SET feedback_status = ? WHERE id = ?",
            request.feedbackStatus, id
        )
        logger.info("Updated ad_headlines: {} row(s) affected", updated)

        // 5. Usuń stary dokument z VectorStore (jeśli istniał) i dodaj nowy
        //    Szukamy po metadata match — usuwamy po id posta
        removeExistingVectorEntry(id, request.userId)

        val document = Document.builder()
            .text(classification.embeddingText)
            .metadata(
                mapOf(
                    "feedback_status" to request.feedbackStatus,
                    "user_id" to request.userId.toString(),
                    "post_tone" to classification.postTone,
                    "post_length" to postLength,
                    "service_type" to classification.serviceType,
                    "car_brand" to classification.carBrand,
                    "full_content" to postContent,
                    "source_post_id" to id.toString()
                )
            )
            .build()

        vectorStore.add(listOf(document))
        logger.info(
            "Indexed in VectorStore: postId={}, tone={}, length={}, service={}, brand={}, embedding='{}'",
            id, classification.postTone, postLength, classification.serviceType,
            classification.carBrand, classification.embeddingText.take(60)
        )

        return ResponseEntity.ok(
            PostFeedbackResponse(
                postId = id,
                feedbackStatus = request.feedbackStatus,
                classification = classification,
                postLength = postLength,
                indexed = true
            )
        )
    }

    /**
     * GET /api/posts/{id}
     * Zwraca dane posta z bazy.
     */
    @GetMapping("/{id}")
    fun getPost(@PathVariable id: Long): ResponseEntity<Map<String, Any?>> {
        val rows = jdbcTemplate.queryForList(
            "SELECT id, headline_text, feedback_status, user_id FROM ad_headlines WHERE id = ?", id
        )
        if (rows.isEmpty()) {
            return ResponseEntity.notFound().build()
        }
        return ResponseEntity.ok(rows.first())
    }

    /**
     * GET /api/posts
     * Lista wszystkich postów.
     */
    @GetMapping
    fun listPosts(): List<Map<String, Any?>> {
        return jdbcTemplate.queryForList(
            "SELECT id, headline_text, feedback_status, user_id FROM ad_headlines ORDER BY id"
        )
    }

    /**
     * POST /api/posts
     * Tworzy nowy post w bazie.
     */
    @PostMapping
    fun createPost(@RequestBody request: CreatePostRequest): ResponseEntity<Map<String, Any?>> {
        jdbcTemplate.update(
            "INSERT INTO ad_headlines (headline_text, user_id) VALUES (?, ?)",
            request.text, request.userId
        )

        val created = jdbcTemplate.queryForMap(
            "SELECT id, headline_text, feedback_status, user_id FROM ad_headlines WHERE headline_text = ? AND user_id = ? ORDER BY id DESC LIMIT 1",
            request.text, request.userId
        )

        logger.info("Created post id={} for userId={}", created["id"], request.userId)
        return ResponseEntity.ok(created)
    }

    private fun removeExistingVectorEntry(postId: Long, userId: Long) {
        try {
            // Szukamy dokumentów z tym source_post_id i user_id
            // Spring AI VectorStore nie ma wbudowanego deleteByMetadata,
            // więc czyścimy bezpośrednio SQL
            val deleted = jdbcTemplate.update(
                """DELETE FROM vector_store 
                   WHERE metadata->>'source_post_id' = ? 
                   AND metadata->>'user_id' = ?""",
                postId.toString(), userId.toString()
            )
            if (deleted > 0) {
                logger.info("Removed {} existing vector entries for postId={}, userId={}", deleted, postId, userId)
            }
        } catch (e: Exception) {
            logger.warn("Could not remove existing vector entry for postId={}: {}", postId, e.message)
        }
    }
}
