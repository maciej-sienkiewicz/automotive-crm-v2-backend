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
import pl.detailing.crm.vehicle.VehicleMetadataService
import pl.detailing.crm.vehicle.VehicleModelNormalizer

@Service
class OpenAiLeadAnalyzer(
    @Qualifier("leadAnalysisChatClient") private val chatClient: ChatClient,
    private val vehicleMetadataService: VehicleMetadataService,
    private val vehicleModelNormalizer: VehicleModelNormalizer
) : LeadAnalyzer {

    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun analyze(
        leadMessage: String,
        preExtractedNeeds: List<String>,
        catalogServices: List<CatalogService>,
        preExtractedVehicleMake: String?,
        preExtractedVehicleModel: String?
    ): LeadAnalysisResult = withContext(Dispatchers.IO) {
        val brands = vehicleMetadataService.getBrands()

        val response = try {
            chatClient.prompt()
                .user(buildUserPrompt(leadMessage, preExtractedNeeds, catalogServices, brands, preExtractedVehicleMake, preExtractedVehicleModel))
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

        // Normalize brand: case-insensitive exact match against known brands list
        val normalizedBrand = response.vehicleBrand
            ?.takeIf { it.isNotBlank() }
            ?.let { llmBrand -> brands.find { it.equals(llmBrand, ignoreCase = true) } }

        // Normalize model: exact case-insensitive match first, then token-normalized fallback
        val normalizedModel = normalizedBrand?.let { brand ->
            val models = vehicleMetadataService.getModelsForBrand(brand)
            val llmModel = response.vehicleModel?.takeIf { it.isNotBlank() } ?: return@let null
            models.find { it.equals(llmModel, ignoreCase = true) }
                ?: models.find { tokenNormalize(it) == tokenNormalize(llmModel) }
        }

        if (response.vehicleBrand != null && normalizedBrand == null) {
            log.warn("[LEAD_ANALYZER] LLM returned unknown vehicleBrand='{}', ignoring", response.vehicleBrand)
        }

        val finalModel = if (normalizedBrand != null && response.vehicleModel != null && normalizedModel == null) {
            log.debug("[LEAD_ANALYZER] String match failed for vehicleModel='{}', brand='{}' — falling back to LLM normalizer", response.vehicleModel, normalizedBrand)
            vehicleModelNormalizer.normalizeModel(normalizedBrand, response.vehicleModel, leadMessage)
                .also { result ->
                    if (result == response.vehicleModel) {
                        log.warn("[LEAD_ANALYZER] Could not normalize vehicleModel='{}' for brand='{}', keeping raw value", response.vehicleModel, normalizedBrand)
                    } else {
                        log.debug("[LEAD_ANALYZER] LLM normalizer: vehicleModel='{}' → '{}'", response.vehicleModel, result)
                    }
                }
        } else {
            normalizedModel
        }

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
            vehicleModel = finalModel,
            summary = response.summary.takeIf { it.isNotBlank() }
        )
    }

    /**
     * Strips non-alphanumeric characters and lowercases for fuzzy model comparison.
     * "3-Series" and "3 Series" both become "3series".
     */
    private fun tokenNormalize(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]"), "")

    /**
     * Single combined prompt: extraction + matching + vehicle identification in one LLM call.
     * Pre-extracted needs and vehicle hints (from email classifier) improve accuracy.
     * Brands list is compact (~1 KB) — included verbatim so LLM returns exact canonical string.
     */
    private fun buildUserPrompt(
        leadMessage: String,
        preExtractedNeeds: List<String>,
        catalogServices: List<CatalogService>,
        brands: List<String>,
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
        appendLine()

        appendLine("Lista dozwolonych marek pojazdów (wybierz DOKŁADNIE jedną z poniższej listy lub null):")
        appendLine(brands.joinToString(", "))
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
