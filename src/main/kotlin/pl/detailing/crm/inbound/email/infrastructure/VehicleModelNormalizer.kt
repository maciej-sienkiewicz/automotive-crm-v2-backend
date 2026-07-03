package pl.detailing.crm.inbound.email.infrastructure

import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import pl.detailing.crm.vehicle.VehicleMetadataService

@Component
class VehicleModelNormalizer(
    private val vehicleMetadataService: VehicleMetadataService,
    @Qualifier("inboundEmailChatClient") private val chatClient: ChatClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Normalizes raw LLM-extracted make to a canonical name from our catalog.
     * Pure in-code: no LLM call, no tokens.
     */
    fun normalizeMake(rawMake: String): String? {
        val lower = rawMake.trim().lowercase()
        return vehicleMetadataService.getBrands()
            .firstOrNull { it.lowercase() == lower }
            ?: vehicleMetadataService.getBrands()
                .firstOrNull { it.lowercase().contains(lower) || lower.contains(it.lowercase()) }
    }

    /**
     * Normalizes raw LLM-extracted model to a canonical name from our catalog for the given make.
     * Makes a small LLM call with only that brand's model list (~50 items max).
     * Returns null if model can't be mapped or make is unknown.
     */
    suspend fun normalizeModel(canonicalMake: String, rawModel: String): String? =
        withContext(Dispatchers.IO) {
            val models = vehicleMetadataService.getModelsForBrand(canonicalMake)
            if (models.isEmpty()) return@withContext rawModel

            val modelList = models.joinToString(", ")
            val prompt = """
                Marka: $canonicalMake
                Dostępne modele: $modelList

                Klient napisał: "$rawModel"

                Zwróć DOKŁADNĄ nazwę modelu z listy powyżej, która najlepiej odpowiada temu co napisał klient.
                Jeśli żaden model nie pasuje, zwróć null.
            """.trimIndent()

            val response = try {
                chatClient.prompt()
                    .user(prompt)
                    .call()
                    .entity(ModelNormalizationResponse::class.java)
            } catch (e: Exception) {
                log.warn("[VEHICLE_NORMALIZER] Model normalization failed for make='{}' rawModel='{}': {}", canonicalMake, rawModel, e.message)
                return@withContext rawModel
            }

            val normalized = response?.normalizedModel
            log.debug("[VEHICLE_NORMALIZER] make='{}' rawModel='{}' → normalizedModel='{}'", canonicalMake, rawModel, normalized)
            normalized ?: rawModel
        }

    private data class ModelNormalizationResponse(
        @JsonProperty("normalizedModel")
        val normalizedModel: String?
    )
}
