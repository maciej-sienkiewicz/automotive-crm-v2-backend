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
    @Qualifier("vehicleNormalizerChatClient") private val chatClient: ChatClient
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
     * Normalizes vehicle model using the original email body as context.
     * Sends only this brand's model list (~50 items) — never the full 3600-line catalog.
     * Returns rawModel unchanged if the brand is unknown or LLM call fails.
     */
    suspend fun normalizeModel(canonicalMake: String, rawModel: String, emailBody: String): String? =
        withContext(Dispatchers.IO) {
            val models = vehicleMetadataService.getModelsForBrand(canonicalMake)
            if (models.isEmpty()) return@withContext rawModel

            val modelList = models.joinToString("\n") { "- $it" }
            val prompt = """
                Klient napisał e-mail z zapytaniem o usługę detailingu. Zidentyfikowano markę pojazdu: $canonicalMake.

                Treść e-maila:
                ---
                ${emailBody.take(1000)}
                ---

                Dostępne modele $canonicalMake w naszym systemie:
                $modelList

                Zadanie: Wskaż, który model z powyższej listy odpowiada pojazdowi wymienionemu w e-mailu.
                Zwróć DOKŁADNĄ nazwę modelu z listy (wielkość liter musi się zgadzać).
                Jeśli nie jesteś pewien lub żaden model nie pasuje, zwróć null.
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

            val normalized = response?.normalizedModel?.takeIf { it.isNotBlank() }
            log.debug("[VEHICLE_NORMALIZER] make='{}' rawModel='{}' → normalizedModel='{}'", canonicalMake, rawModel, normalized)
            normalized ?: rawModel
        }

    private data class ModelNormalizationResponse(
        @JsonProperty("normalizedModel")
        val normalizedModel: String?
    )
}
