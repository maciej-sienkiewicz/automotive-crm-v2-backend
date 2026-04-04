package pl.detailing.crm.instagram.ai.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Baza wektorowa dla postów Instagramowych — oparta o pgvector + JdbcTemplate.
 *
 * Schemat tabeli (tworzony automatycznie przy starcie):
 * ```sql
 * instagram_post_vectors (
 *   id       UUID PRIMARY KEY,
 *   content  TEXT NOT NULL,       -- skondensowany tekst do embeddingu (embeddingText)
 *   metadata JSONB NOT NULL,      -- feedback_status, studio_id, post_tone, ...
 *   embedding vector(1536)        -- wektor semantyczny (text-embedding-3-small)
 * )
 * ```
 *
 * Wyszukiwanie podobieństwa używa odległości kosinusowej (`<=>` operator pgvector)
 * z dynamicznym filtrem na polach JSONB metadanych.
 *
 * Wynikiem wyszukiwania jest `full_content` z metadanych — pełna treść posta
 * przekazywana do promptów few-shot (nie skrócony embeddingText).
 */
@Service
class InstagramPostVectorStore(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    @Value("\${instagram.ai.vectorstore.embedding-dimensions:1536}") private val dimensions: Int
) {
    private val logger = LoggerFactory.getLogger(InstagramPostVectorStore::class.java)

    // ── Inicjalizacja schematu ─────────────────────────────────────────────────

    @PostConstruct
    fun initSchema() {
        try {
            jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector")
            logger.info("pgvector extension ensured")
        } catch (e: Exception) {
            logger.warn("Could not create pgvector extension (may require superuser): {}", e.message)
        }

        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS instagram_post_vectors (
                id        UUID        PRIMARY KEY,
                content   TEXT        NOT NULL,
                metadata  JSONB       NOT NULL DEFAULT '{}'::jsonb,
                embedding vector($dimensions)
            )
            """.trimIndent()
        )

        jdbcTemplate.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_igv_embedding
                ON instagram_post_vectors USING hnsw (embedding vector_cosine_ops)
            """.trimIndent()
        )

        jdbcTemplate.execute(
            """
            CREATE INDEX IF NOT EXISTS idx_igv_metadata
                ON instagram_post_vectors USING gin (metadata)
            """.trimIndent()
        )

        logger.info("instagram_post_vectors schema initialized (dimensions={})", dimensions)
    }

    // ── Zapis ─────────────────────────────────────────────────────────────────

    /**
     * Dodaje dokument do bazy wektorowej.
     *
     * @param id            Unikalny identyfikator dokumentu
     * @param embeddingText Skondensowany tekst do embeddingu (np. "BMW M4 PPF premium maska zderzak")
     * @param embedding     Wektor float[1536] wygenerowany przez OpenAI
     * @param metadata      Metadane dokumentu (feedback_status, studio_id, post_tone, itd.)
     */
    fun add(
        id: UUID,
        embeddingText: String,
        embedding: FloatArray,
        metadata: Map<String, String>
    ) {
        val metadataJson = objectMapper.writeValueAsString(metadata)
        val vectorStr = embedding.toVectorString()

        jdbcTemplate.update(
            """
            INSERT INTO instagram_post_vectors (id, content, metadata, embedding)
            VALUES (?::uuid, ?, ?::jsonb, ?::vector)
            ON CONFLICT (id) DO UPDATE
                SET content   = EXCLUDED.content,
                    metadata  = EXCLUDED.metadata,
                    embedding = EXCLUDED.embedding
            """.trimIndent(),
            id.toString(), embeddingText, metadataJson, vectorStr
        )
        logger.debug("Added vector document id={}", id)
    }

    // ── Wyszukiwanie podobieństwa ──────────────────────────────────────────────

    /**
     * Wyszukuje [topK] najbardziej podobnych dokumentów do [queryEmbedding]
     * spełniających warunki [metadataFilter].
     *
     * Zwraca listę `full_content` z metadanych — pełnych treści postów
     * gotowych do użycia jako przykłady few-shot w promptach.
     *
     * @param queryEmbedding  Wektor zapytania (embedding tematu)
     * @param topK            Liczba wyników
     * @param metadataFilter  Mapa klucz→wartość filtrów JSONB (null = pomiń filtr)
     */
    fun similaritySearch(
        queryEmbedding: FloatArray,
        topK: Int,
        metadataFilter: Map<String, String?>
    ): List<String> {
        val vectorStr = queryEmbedding.toVectorString()
        val (whereClause, filterParams) = buildWhereClause(metadataFilter)

        // Parametry: filtry JSONB + wektor × 2 (WHERE i ORDER BY) + LIMIT
        val params: Array<Any> = (filterParams + listOf(vectorStr, vectorStr, topK)).toTypedArray()

        val results = jdbcTemplate.queryForList(
            """
            SELECT metadata->>'full_content' AS full_content
            FROM instagram_post_vectors
            WHERE $whereClause
              AND embedding IS NOT NULL
            ORDER BY embedding <=> ?::vector
            LIMIT ?
            """.trimIndent(),
            *params
        )

        return results.mapNotNull { it["full_content"] as? String }
            .also { logger.debug("Similarity search: {} results, filter={}", it.size, metadataFilter) }
    }

    // ── Usuwanie ──────────────────────────────────────────────────────────────

    /**
     * Usuwa dokumenty spełniające dokładne wartości [metadataFilter].
     * Wszystkie podane klucze muszą być niepuste (null-e są pomijane).
     *
     * @return liczba usuniętych wierszy
     */
    fun delete(metadataFilter: Map<String, String>): Int {
        if (metadataFilter.isEmpty()) return 0

        val (whereClause, params) = buildWhereClause(metadataFilter)
        val deleted = jdbcTemplate.update(
            "DELETE FROM instagram_post_vectors WHERE $whereClause",
            *params.toTypedArray()
        )
        logger.debug("Deleted {} vector documents, filter={}", deleted, metadataFilter)
        return deleted
    }

    // ── Budowanie WHERE ────────────────────────────────────────────────────────

    private fun buildWhereClause(filter: Map<String, String?>): Pair<String, List<Any>> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()

        filter.forEach { (key, value) ->
            if (value != null) {
                conditions.add("metadata->>'$key' = ?")
                params.add(value)
            }
        }

        val clause = if (conditions.isEmpty()) "1=1" else conditions.joinToString(" AND ")
        return clause to params
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun FloatArray.toVectorString(): String =
        joinToString(separator = ",", prefix = "[", postfix = "]")
}
