package pl.detailing.crm.leads.estimation.infrastructure

import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import pl.detailing.crm.leads.estimation.domain.CatalogService
import pl.detailing.crm.leads.estimation.domain.LeadAnalysisResult
import pl.detailing.crm.leads.estimation.domain.LeadAnalyzer

@Service
class OpenAiLeadAnalyzer(
    @Qualifier("leadAnalysisChatClient") private val chatClient: ChatClient
) : LeadAnalyzer {

    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun analyze(
        leadMessage: String,
        preExtractedNeeds: List<String>,
        catalogServices: List<CatalogService>
    ): LeadAnalysisResult = withContext(Dispatchers.IO) {
        val response = try {
            chatClient.prompt()
                .user(buildUserPrompt(leadMessage, preExtractedNeeds, catalogServices))
                .call()
                .entity(AnalysisLlmResponse::class.java)
        } catch (e: Exception) {
            log.error("[LEAD_ANALYZER] LLM call failed: {}", e.message)
            throw e
        }

        if (response == null) {
            log.warn("[LEAD_ANALYZER] LLM returned null response")
            return@withContext LeadAnalysisResult(emptyList(), emptyList(), emptyList())
        }

        // Validate returned service IDs — reject hallucinated IDs not in catalog
        val validIds = catalogServices.map { it.id }.toSet()
        val safeMatchedIds = response.matchedServices
            .map { it.serviceId }
            .filter { id ->
                val valid = id in validIds
                if (!valid) log.warn("[LEAD_ANALYZER] LLM returned unknown serviceId='{}', ignoring", id)
                valid
            }

        log.info(
            "[LEAD_ANALYZER] extracted={} matched={} unmatched={} | reasoning='{}'",
            response.extractedNeeds.size, safeMatchedIds.size,
            response.unmatchedNeeds.size, response.reasoning
        )

        LeadAnalysisResult(
            extractedNeeds = response.extractedNeeds,
            matchedServiceIds = safeMatchedIds,
            unmatchedNeeds = response.unmatchedNeeds
        )
    }

    /**
     * Single combined prompt: extraction + matching in one LLM call.
     * Pre-extracted needs (from email classifier) are passed as hints to improve accuracy.
     */
    private fun buildUserPrompt(
        leadMessage: String,
        preExtractedNeeds: List<String>,
        catalogServices: List<CatalogService>
    ): String = buildString {
        if (preExtractedNeeds.isNotEmpty()) {
            appendLine("Wstępnie wyodrębnione potrzeby klienta (z klasyfikacji wiadomości):")
            preExtractedNeeds.forEach { appendLine("  - $it") }
            appendLine()
        }

        appendLine("Treść wiadomości klienta:")
        appendLine("---")
        appendLine(leadMessage.take(3000))
        appendLine("---")
        appendLine()
        appendLine("Katalog usług studia (${catalogServices.size} pozycji):")
        catalogServices.forEach { service ->
            appendLine("  ID: ${service.id} | Nazwa: ${service.name}")
        }
    }

    /**
     * Structured output schema. Field order is intentional:
     * reasoning first (CoT scratchpad) → better extractedNeeds → better matching.
     */
    private data class AnalysisLlmResponse(
        @JsonProperty("reasoning")
        val reasoning: String,

        @JsonProperty("extractedNeeds")
        val extractedNeeds: List<String>,

        @JsonProperty("matchedServices")
        val matchedServices: List<MatchedServiceItem>,

        @JsonProperty("unmatchedNeeds")
        val unmatchedNeeds: List<String>
    )

    private data class MatchedServiceItem(
        @JsonProperty("serviceId")
        val serviceId: String,

        @JsonProperty("matchedNeed")
        val matchedNeed: String
    )
}
