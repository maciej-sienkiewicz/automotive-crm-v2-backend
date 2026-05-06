package pl.detailing.crm.inbound.email.infrastructure

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import pl.detailing.crm.inbound.email.domain.EmailClassificationResult
import pl.detailing.crm.inbound.email.domain.EmailLeadClassifier

@Service
class OpenAiEmailLeadClassifier(
    @Qualifier("inboundEmailChatClient") private val chatClient: ChatClient,
    private val objectMapper: ObjectMapper
) : EmailLeadClassifier {

    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun classify(from: String, subject: String?, body: String): EmailClassificationResult =
        withContext(Dispatchers.IO) {
            val userPrompt = buildUserPrompt(from, subject, body)

            val raw = try {
                chatClient.prompt()
                    .user(userPrompt)
                    .call()
                    .content()
            } catch (e: Exception) {
                log.error("[EMAIL_CLASSIFIER] LLM call failed for from={}: {}", from, e.message)
                return@withContext EmailClassificationResult.NotALead
            }

            if (raw.isNullOrBlank()) {
                log.warn("[EMAIL_CLASSIFIER] LLM returned empty response for from={}", from)
                return@withContext EmailClassificationResult.NotALead
            }

            parseResponse(raw, from)
        }

    private fun buildUserPrompt(from: String, subject: String?, body: String): String = """
        Przeanalizuj poniższy e-mail i oceń, czy zawiera zapytanie o usługę detailingu, wycenę lub ofertę.

        Nadawca: $from
        Temat: ${subject?.take(500) ?: "(brak tematu)"}
        Treść: ${body.take(2000)}

        Odpowiedz WYŁĄCZNIE w formacie JSON:
        {
          "isLead": true lub false,
          "extractedName": "imię i nazwisko nadawcy jeśli zawarte w treści, null jeśli brak",
          "summary": "1-2 zdaniowe podsumowanie zapytania po polsku, null jeśli nie jest leadem"
        }
    """.trimIndent()

    private fun parseResponse(raw: String, from: String): EmailClassificationResult {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()

        return try {
            val response = objectMapper.readValue(cleaned, LlmClassificationResponse::class.java)

            if (!response.isLead) {
                EmailClassificationResult.NotALead
            } else {
                EmailClassificationResult.LeadDetected(
                    extractedName = response.extractedName?.takeIf { it.isNotBlank() && it != "null" },
                    summary = response.summary?.takeIf { it.isNotBlank() } ?: "Zapytanie ofertowe"
                )
            }
        } catch (e: Exception) {
            log.warn("[EMAIL_CLASSIFIER] Failed to parse LLM JSON for from={}, raw='{}': {}", from, cleaned, e.message)
            EmailClassificationResult.NotALead
        }
    }

    private data class LlmClassificationResponse(
        @JsonProperty("isLead") val isLead: Boolean = false,
        @JsonProperty("extractedName") val extractedName: String? = null,
        @JsonProperty("summary") val summary: String? = null
    )
}
