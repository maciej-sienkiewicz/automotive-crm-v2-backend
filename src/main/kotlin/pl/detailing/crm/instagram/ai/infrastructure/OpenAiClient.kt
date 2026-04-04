package pl.detailing.crm.instagram.ai.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Klient HTTP dla OpenAI API.
 *
 * Pokrywa dwa endpointy:
 *  - POST /v1/chat/completions  – generowanie tekstu (chat)
 *  - POST /v1/embeddings        – generowanie wektorów semantycznych
 *
 * Wszystkie wywołania są suspend i wykonywane na [Dispatchers.IO].
 *
 * Tryb JSON (`response_format: json_object`) wymusza zwrot poprawnego JSON-a przez model.
 * Prompt systemowy musi zawierać opis oczekiwanej struktury — klasa wywołująca jest za to odpowiedzialna.
 */
@Service
class OpenAiClient(
    private val objectMapper: ObjectMapper,
    @Value("\${instagram.ai.openai.api-key:}") private val apiKey: String,
    @Value("\${instagram.ai.openai.chat.model:gpt-4o-mini}") private val chatModel: String,
    @Value("\${instagram.ai.openai.chat.temperature:0.7}") private val temperature: Double,
    @Value("\${instagram.ai.openai.embedding.model:text-embedding-3-small}") private val embeddingModel: String,
    @Value("\${instagram.ai.openai.timeout-seconds:60}") private val timeoutSeconds: Long
) {
    private val logger = LoggerFactory.getLogger(OpenAiClient::class.java)

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    // ── Chat ─────────────────────────────────────────────────────────────────────

    /**
     * Wywołuje OpenAI Chat Completions z trybem JSON.
     * Parsuje odpowiedź do [T] za pomocą Jackson.
     *
     * System prompt MUSI opisywać oczekiwaną strukturę JSON, np.:
     * "Zwróć odpowiedź jako JSON: { \"pole1\": \"...\", \"pole2\": \"...\" }"
     *
     * @throws OpenAiConfigException gdy klucz API nie jest skonfigurowany
     * @throws OpenAiApiException    gdy OpenAI zwróci błąd HTTP
     * @throws OpenAiParsingException gdy odpowiedź nie da się sparsować do [T]
     */
    suspend fun <T : Any> chatStructured(
        systemMessage: String,
        userMessage: String,
        responseType: Class<T>
    ): T {
        val raw = chat(systemMessage, userMessage)
        return try {
            objectMapper.readValue(raw, responseType)
        } catch (e: Exception) {
            logger.error("Failed to parse LLM response as {}: '{}'", responseType.simpleName, raw.take(200))
            throw OpenAiParsingException(
                "Nie udało się sparsować odpowiedzi LLM jako ${responseType.simpleName}: ${e.message}",
                e
            )
        }
    }

    /**
     * Wywołuje OpenAI Chat Completions i zwraca surowy tekst odpowiedzi.
     * Tryb JSON jest wymuszony — odpowiedź jest zawsze poprawnym JSON-em.
     */
    suspend fun chat(systemMessage: String, userMessage: String): String =
        withContext(Dispatchers.IO) {
            val body = buildChatRequestBody(systemMessage, userMessage)
            logger.debug("Chat request: model={}, systemMsg={}chars", chatModel, systemMessage.length)

            val responseJson = post(CHAT_ENDPOINT, body)
            val content = extractChatContent(responseJson)

            logger.debug("Chat response: {}chars", content.length)
            content
        }

    // ── Embeddings ────────────────────────────────────────────────────────────────

    /**
     * Generuje embedding wektorowy dla podanego tekstu.
     *
     * @return wektor float[1536] (text-embedding-3-small)
     * @throws OpenAiApiException gdy OpenAI zwróci błąd HTTP
     */
    suspend fun embedding(text: String): FloatArray =
        withContext(Dispatchers.IO) {
            val body = objectMapper.writeValueAsString(
                mapOf("model" to embeddingModel, "input" to text)
            )
            logger.debug("Embedding request: model={}, text={}chars", embeddingModel, text.length)

            val responseJson = post(EMBEDDINGS_ENDPOINT, body)
            extractEmbedding(responseJson)
        }

    // ── HTTP ──────────────────────────────────────────────────────────────────────

    private fun post(path: String, body: String): String {
        check(apiKey.isNotBlank()) {
            "Klucz API OpenAI nie jest skonfigurowany. Ustaw zmienną środowiskową OPENAI_API_KEY."
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$BASE_URL$path"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer $apiKey")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() != 200) {
            logger.error("OpenAI API error {}: {}", response.statusCode(), response.body().take(500))
            throw OpenAiApiException(
                "OpenAI zwrócił błąd HTTP ${response.statusCode()}: ${response.body().take(200)}"
            )
        }

        return response.body()
    }

    // ── Budowanie żądania ─────────────────────────────────────────────────────────

    private fun buildChatRequestBody(systemMessage: String, userMessage: String): String =
        objectMapper.writeValueAsString(
            mapOf(
                "model" to chatModel,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to systemMessage),
                    mapOf("role" to "user", "content" to userMessage)
                ),
                "response_format" to mapOf("type" to "json_object"),
                "temperature" to temperature
            )
        )

    // ── Parsowanie odpowiedzi ─────────────────────────────────────────────────────

    private fun extractChatContent(responseJson: String): String {
        val root = objectMapper.readTree(responseJson)
        return root.path("choices").path(0).path("message").path("content").asText()
            .also { check(it.isNotBlank()) { "OpenAI zwrócił pustą odpowiedź" } }
    }

    private fun extractEmbedding(responseJson: String): FloatArray {
        val root = objectMapper.readTree(responseJson)
        val embeddingNode = root.path("data").path(0).path("embedding")
        return FloatArray(embeddingNode.size()) { embeddingNode[it].floatValue() }
    }

    companion object {
        private const val BASE_URL = "https://api.openai.com"
        private const val CHAT_ENDPOINT = "/v1/chat/completions"
        private const val EMBEDDINGS_ENDPOINT = "/v1/embeddings"
    }
}

// ── Wyjątki ───────────────────────────────────────────────────────────────────

class OpenAiApiException(message: String) : RuntimeException(message)
class OpenAiParsingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
