package pl.detailing.crm.inbound.email.infrastructure

import com.fasterxml.jackson.annotation.JsonProperty
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
    @Qualifier("inboundEmailChatClient") private val chatClient: ChatClient
) : EmailLeadClassifier {

    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun classify(from: String, subject: String?, body: String): EmailClassificationResult =
        withContext(Dispatchers.IO) {
            val userPrompt = buildUserPrompt(from, subject, body)

            val response = try {
                chatClient.prompt()
                    .user(userPrompt)
                    .call()
                    .entity(EmailLlmResponse::class.java)
            } catch (e: Exception) {
                log.error("[EMAIL_CLASSIFIER] LLM call failed for from='{}': {}", from, e.message)
                return@withContext EmailClassificationResult.NotALead
            }

            if (response == null) {
                log.warn("[EMAIL_CLASSIFIER] LLM returned null for from='{}'", from)
                return@withContext EmailClassificationResult.NotALead
            }

            log.debug(
                "[EMAIL_CLASSIFIER] from='{}' → isLead={}, make={}, model={}, year={}, services={}",
                from, response.isLead, response.vehicleMake, response.vehicleModel,
                response.vehicleYear, response.requestedServices
            )

            if (!response.isLead) {
                return@withContext EmailClassificationResult.NotALead
            }

            EmailClassificationResult.LeadDetected(
                extractedName = response.extractedName?.takeIf { it.isNotBlank() },
                summary = response.summary?.takeIf { it.isNotBlank() } ?: "Zapytanie ofertowe",
                vehicleMake = response.vehicleMake?.takeIf { it.isNotBlank() },
                vehicleModel = response.vehicleModel?.takeIf { it.isNotBlank() },
                vehicleYear = response.vehicleYear,
                requestedServices = response.requestedServices ?: emptyList()
            )
        }

    private fun buildUserPrompt(from: String, subject: String?, body: String): String = """
        Przeanalizuj poniższy e-mail i oceń, czy jest to zapytanie o usługę detailingu samochodowego, wycenę lub ofertę.
        Jeśli tak, wyciągnij dostępne informacje o pojeździe i usługach.

        Nadawca: $from
        Temat: ${subject?.take(500) ?: "(brak tematu)"}
        Treść:
        ${body.take(3000)}
    """.trimIndent()

    /**
     * Schema for OpenAI Structured Outputs.
     * All vehicle/service fields are nullable — extract only what is explicitly stated in the email.
     */
    private data class EmailLlmResponse(
        @JsonProperty("isLead")
        val isLead: Boolean,

        @JsonProperty("extractedName")
        val extractedName: String?,

        @JsonProperty("summary")
        val summary: String?,

        @JsonProperty("vehicleMake")
        val vehicleMake: String?,

        @JsonProperty("vehicleModel")
        val vehicleModel: String?,

        @JsonProperty("vehicleYear")
        val vehicleYear: Int?,

        @JsonProperty("requestedServices")
        val requestedServices: List<String>?
    )
}
