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
import pl.detailing.crm.vehicle.VehicleMetadataService
import pl.detailing.crm.vehicle.VehicleModelNormalizer

@Service
class OpenAiEmailLeadClassifier(
    @Qualifier("inboundEmailChatClient") private val chatClient: ChatClient,
    private val vehicleModelNormalizer: VehicleModelNormalizer,
    private val vehicleMetadataService: VehicleMetadataService
) : EmailLeadClassifier {

    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun classify(from: String, subject: String?, body: String): EmailClassificationResult =
        withContext(Dispatchers.IO) {
            val bodySnippet = body.take(300).replace("\n", " ↵ ")
            log.debug("[EMAIL_CLASSIFIER] Sending to LLM: from='{}' subject='{}' body='{}'", from, subject, bodySnippet)

            val response = try {
                chatClient.prompt()
                    .user(buildUserPrompt(from, subject, body))
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

            // Log reasoning for observability — valuable for tuning the prompt over time
            log.info(
                "[EMAIL_CLASSIFIER] from='{}' isLead={} | reasoning='{}' | make={} model={} year={} services={} | body='{}'",
                from, response.isLead, response.reasoning,
                response.vehicleMake, response.vehicleModel, response.vehicleYear, response.requestedServices,
                bodySnippet
            )

            if (!response.isLead) {
                return@withContext EmailClassificationResult.NotALead
            }

            val rawMake = response.vehicleMake?.takeIf { it.isNotBlank() }
            val rawModel = response.vehicleModel?.takeIf { it.isNotBlank() }

            val canonicalMake = rawMake?.let { vehicleModelNormalizer.normalizeMake(it) } ?: rawMake
            val canonicalModel = if (canonicalMake != null && rawModel != null) {
                vehicleModelNormalizer.normalizeModel(canonicalMake, rawModel, body)
            } else {
                rawModel
            }

            if (rawMake != canonicalMake || rawModel != canonicalModel) {
                log.debug("[EMAIL_CLASSIFIER] Vehicle normalized: make='{}'->'{}' model='{}'->'{}'", rawMake, canonicalMake, rawModel, canonicalModel)
            }

            EmailClassificationResult.LeadDetected(
                extractedName = response.extractedName?.takeIf { it.isNotBlank() },
                summary = response.summary?.takeIf { it.isNotBlank() } ?: "Zapytanie ofertowe",
                vehicleMake = canonicalMake,
                vehicleModel = canonicalModel,
                vehicleYear = response.vehicleYear,
                requestedServices = response.requestedServices ?: emptyList()
            )
        }

    /**
     * User prompt intentionally contains only the raw email data.
     * All instructions and rules live in the system prompt (InboundEmailAiConfig).
     * This separation improves instruction-following on gpt-4o-mini.
     */
    private fun buildUserPrompt(from: String, subject: String?, body: String): String {
        val brands = vehicleMetadataService.getBrands().joinToString(", ")
        return """
            Nadawca: $from
            Temat: ${subject?.take(500) ?: "(brak tematu)"}

            ---
            ${body.take(3000)}
            ---

            Lista dozwolonych marek pojazdów — vehicleMake musi być DOKŁADNIE jedną z poniższych lub null:
            $brands
        """.trimIndent()
    }

    /**
     * Schema for OpenAI Structured Outputs.
     *
     * Field ordering is intentional: [reasoning] comes first so the model generates
     * its chain-of-thought before committing to [isLead]. This "scratchpad before answer"
     * technique measurably improves classification accuracy on smaller models like gpt-4o-mini.
     *
     * All extraction fields are nullable: the model must return null rather than guess.
     */
    private data class EmailLlmResponse(
        @JsonProperty("reasoning")
        val reasoning: String,

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
