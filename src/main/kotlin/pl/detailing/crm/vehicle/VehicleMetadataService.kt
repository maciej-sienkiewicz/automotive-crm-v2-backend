package pl.detailing.crm.vehicle

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import jakarta.annotation.PostConstruct
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import org.slf4j.LoggerFactory

data class BrandWithModels(
    val marka: String,
    val modele: List<String>
)

@Service
class VehicleMetadataService {
    private val logger = LoggerFactory.getLogger(VehicleMetadataService::class.java)
    private var cachedData: List<BrandWithModels> = emptyList()

    @PostConstruct
    fun init() {
        try {
            val resource = ClassPathResource("db/marki_modele_final.json")
            val mapper = jacksonObjectMapper()
            cachedData = mapper.readValue<List<BrandWithModels>>(resource.inputStream)
            logger.info("Loaded {} vehicle brands with models from JSON", cachedData.size)
        } catch (e: Exception) {
            logger.error("Failed to load vehicle metadata from JSON", e)
        }
    }

    fun getAllBrandsWithModels(): List<BrandWithModels> = cachedData

    fun getBrands(): List<String> = cachedData.map { it.marka }.sorted()

    fun getModelsForBrand(brand: String): List<String> {
        return cachedData.find { it.marka.equals(brand, ignoreCase = true) }?.modele?.sorted() ?: emptyList()
    }
}
