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
import pl.detailing.crm.vehicle.VehicleCatalogMatcher

@Service
class OpenAiLeadAnalyzer(
    @Qualifier("leadAnalysisChatClient") private val chatClient: ChatClient,
    private val vehicleCatalogMatcher: VehicleCatalogMatcher
) : LeadAnalyzer {

    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun analyze(
        leadMessage: String,
        preExtractedNeeds: List<String>,
        catalogServices: List<CatalogService>,
        preExtractedVehicleMake: String?,
        preExtractedVehicleModel: String?
    ): LeadAnalysisResult = withContext(Dispatchers.IO) {
        val response = try {
            chatClient.prompt()
                .user(buildUserPrompt(leadMessage, preExtractedNeeds, catalogServices, preExtractedVehicleMake, preExtractedVehicleModel))
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

        // Canonicalize the LLM's raw vehicle mention against the catalog (single source of truth).
        // Deterministic-first with LLM fallback handles colloquial models, e.g. "g-wagon" → "Klasa G".
        val vehicleMatch = vehicleCatalogMatcher.resolve(response.vehicleBrand, response.vehicleModel)
        val normalizedBrand = vehicleMatch.brand
        val normalizedModel = vehicleMatch.model

        log.info(
            "[LEAD_ANALYZER] extracted={} matched={} unmatched={} vehicle='{} {}' | summary='{}'",
            response.extractedNeeds.size, safeMatchedIds.size,
            response.unmatchedNeeds.size, normalizedBrand, normalizedModel, response.summary
        )

        LeadAnalysisResult(
            extractedNeeds = response.extractedNeeds,
            matchedServiceIds = safeMatchedIds,
            unmatchedNeeds = response.unmatchedNeeds,
            vehicleBrand = normalizedBrand,
            vehicleModel = normalizedModel,
            summary = response.summary.takeIf { it.isNotBlank() }
        )
    }

    /**
     * Single combined prompt: extraction + matching + vehicle identification in one LLM call.
     * Pre-extracted needs and vehicle hints (from email classifier) improve accuracy.
     * Vehicle make/model are returned raw (as the customer wrote them) — canonicalization
     * against the catalog is done afterwards by VehicleCatalogMatcher.
     */
    private fun buildUserPrompt(
        leadMessage: String,
        preExtractedNeeds: List<String>,
        catalogServices: List<CatalogService>,
        preExtractedVehicleMake: String?,
        preExtractedVehicleModel: String?
    ): String = buildString {
        if (preExtractedNeeds.isNotEmpty()) {
            appendLine("Wstępnie wyodrębnione potrzeby klienta (z klasyfikacji wiadomości):")
            preExtractedNeeds.forEach { appendLine("  - $it") }
            appendLine()
        }

        if (preExtractedVehicleMake != null || preExtractedVehicleModel != null) {
            appendLine("Wstępnie zidentyfikowany pojazd (z klasyfikacji wiadomości):")
            preExtractedVehicleMake?.let { appendLine("  Marka (nieznormalizowana): $it") }
            preExtractedVehicleModel?.let { appendLine("  Model (nieznormalizowany): $it") }
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
     * reasoning first (CoT scratchpad improves extraction quality) → extraction → matching
     * → vehicle identification → summary last (synthesises all of the above).
     */
    private data class AnalysisLlmResponse(
        @JsonProperty("reasoning")
        val reasoning: String,

        @JsonProperty("extractedNeeds")
        val extractedNeeds: List<String>,

        @JsonProperty("matchedServices")
        val matchedServices: List<MatchedServiceItem>,

        @JsonProperty("unmatchedNeeds")
        val unmatchedNeeds: List<String>,

        @JsonProperty("vehicleBrand")
        val vehicleBrand: String?,

        @JsonProperty("vehicleModel")
        val vehicleModel: String?,

        @JsonProperty("summary")
        val summary: String
    )

    private data class MatchedServiceItem(
        @JsonProperty("serviceId")
        val serviceId: String,

        @JsonProperty("matchedNeed")
        val matchedNeed: String
    )
}
