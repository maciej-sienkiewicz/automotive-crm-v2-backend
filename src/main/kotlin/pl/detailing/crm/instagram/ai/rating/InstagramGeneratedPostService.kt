package pl.detailing.crm.instagram.ai.rating

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.instagram.ai.infrastructure.InstagramGeneratedPostEntity
import pl.detailing.crm.instagram.ai.infrastructure.InstagramGeneratedPostRepository
import pl.detailing.crm.instagram.ai.model.GeneratedPostRating
import pl.detailing.crm.instagram.ai.model.GeneratedPostResponse
import pl.detailing.crm.instagram.ai.model.RateGeneratedPostRequest
import pl.detailing.crm.instagram.ai.model.StoredVerificationReport
import pl.detailing.crm.instagram.ai.model.VerifiedGenerationResult
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.ValidationException
import java.time.Instant
import java.util.UUID

/**
 * Zapis, historia i ocena postów wygenerowanych przez AI.
 *
 * Ocena zamyka pętlę uczenia: po zapisaniu werdyktu post trafia do bazy wektorowej
 * (LIKED/DISLIKED), skąd retrieval bierze go jako przykład few-shot przy kolejnych
 * generowaniach dla tego studia.
 */
@Service
class InstagramGeneratedPostService(
    private val repository: InstagramGeneratedPostRepository,
    private val vectorIndexer: GeneratedPostVectorIndexer
) {
    private val logger = LoggerFactory.getLogger(InstagramGeneratedPostService::class.java)
    private val json: ObjectMapper = jacksonObjectMapper().findAndRegisterModules()
    private val ioScope = CoroutineScope(Dispatchers.IO)

    companion object {
        private const val MAX_HISTORY_LIMIT = 100
        private const val MAX_COMMENT_LENGTH = 1000
    }

    /**
     * Zapisuje wygenerowany post — ZAWSZE, także gdy weryfikacja reguł się nie powiodła.
     * Post z odstępstwem od stylu też podlega ocenie, a bez zapisu nie byłoby czego oceniać.
     */
    @Transactional
    fun save(
        studioId: StudioId,
        topic: String,
        additionalContext: String?,
        requestedTone: String?,
        requestedLength: String?,
        result: VerifiedGenerationResult
    ): InstagramGeneratedPostEntity {
        val report = StoredVerificationReport(
            iterations = result.iterations,
            passed = result.verificationPassed,
            verdicts = result.verdicts
        )

        return repository.save(
            InstagramGeneratedPostEntity(
                id = UUID.randomUUID(),
                studioId = studioId.value,
                topic = topic,
                additionalContext = additionalContext,
                requestedTone = requestedTone,
                requestedLength = requestedLength,
                content = result.content,
                verificationReportJson = json.writeValueAsString(report),
                rulesSnapshotJson = json.writeValueAsString(result.appliedRules),
                createdAt = Instant.now()
            )
        )
    }

    @Transactional(readOnly = true)
    fun history(studioId: StudioId, limit: Int): List<GeneratedPostResponse> {
        val effectiveLimit = limit.coerceIn(1, MAX_HISTORY_LIMIT)
        return repository
            .findByStudioIdOrderByCreatedAtDesc(studioId.value, PageRequest.of(0, effectiveLimit))
            .map { it.toResponse() }
    }

    /**
     * Zapisuje ocenę posta i uruchamia (asynchronicznie) indeksację w VectorStore.
     *
     * Ponowna ocena nadpisuje poprzednią — łącznie z wpisem wektorowym, który przy zmianie
     * werdyktu musi zmienić stronę (wzorzec ↔ antywzorzec).
     *
     * Indeksacja idzie poza wątek HTTP, bo wymaga wywołania LLM (klasyfikacja) i embeddingu;
     * jej ewentualne niepowodzenie nie może cofnąć zapisanej oceny.
     */
    @Transactional
    fun rate(studioId: StudioId, postId: UUID, request: RateGeneratedPostRequest): GeneratedPostResponse {
        val post = repository.findByIdAndStudioId(postId, studioId.value)
            ?: throw EntityNotFoundException("Nie znaleziono wygenerowanego posta o id: $postId")

        val comment = request.comment?.trim()?.takeIf { it.isNotEmpty() }

        if (request.rating == GeneratedPostRating.POSITIVE && comment != null) {
            throw ValidationException(
                "Komentarz można dodać wyłącznie przy ocenie negatywnej — " +
                    "przy pozytywnej nie ma czego poprawiać."
            )
        }
        if (comment != null && comment.length > MAX_COMMENT_LENGTH) {
            throw ValidationException("Komentarz może mieć maksymalnie $MAX_COMMENT_LENGTH znaków.")
        }

        post.rating = request.rating.name
        post.ratingComment = comment
        post.ratedAt = Instant.now()
        val saved = repository.save(post)

        ioScope.launch {
            try {
                vectorIndexer.index(studioId, postId, saved.content, request.rating, comment)
            } catch (e: Exception) {
                logger.error(
                    "Failed to index rated post in VectorStore: studioId={}, postId={}: {}",
                    studioId, postId, e.message, e
                )
            }
        }

        logger.info("Generated post rated: studioId={}, postId={}, rating={}", studioId, postId, request.rating)
        return saved.toResponse()
    }

    // ── Mapowanie ─────────────────────────────────────────────────────────────

    private fun InstagramGeneratedPostEntity.toResponse(): GeneratedPostResponse {
        val report = verificationReportJson?.let { raw ->
            runCatching { json.readValue<StoredVerificationReport>(raw) }
                .onFailure { logger.warn("Unreadable verification report for post {}: {}", id, it.message) }
                .getOrNull()
        }
        val rules = runCatching { json.readValue<List<String>>(rulesSnapshotJson) }.getOrDefault(emptyList())

        return GeneratedPostResponse(
            id = id.toString(),
            topic = topic,
            content = content,
            requestedTone = requestedTone,
            requestedLength = requestedLength,
            rating = rating,
            ratingComment = ratingComment,
            verificationPassed = report?.passed,
            iterations = report?.iterations,
            failedRules = report?.verdicts?.filter { !it.passed }?.map { it.ruleText } ?: emptyList(),
            rulesSnapshot = rules,
            createdAt = createdAt.toEpochMilli(),
            ratedAt = ratedAt?.toEpochMilli()
        )
    }
}
