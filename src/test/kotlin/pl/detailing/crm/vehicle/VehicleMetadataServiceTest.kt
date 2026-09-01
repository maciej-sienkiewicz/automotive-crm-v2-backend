package pl.detailing.crm.vehicle

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Serwis nie ma żadnych zależności — czyta tylko JSON z classpath, więc testujemy go
 * bez podnoszenia kontekstu Springa. Wcześniejsza wersja z @SpringBootTest wymagała
 * działającej bazy i Redisa, przez co nie dawała się uruchomić ani lokalnie bez
 * infrastruktury, ani w CI. `init()` wołamy ręcznie — to samo robi @PostConstruct.
 */
class VehicleMetadataServiceTest {

    private val vehicleMetadataService = VehicleMetadataService().apply { init() }

    @Test
    fun `should load brands from json`() {
        val brands = vehicleMetadataService.getBrands()
        assertFalse(brands.isEmpty())
        assertTrue(brands.contains("Audi"))
        assertTrue(brands.contains("Bmw"))
    }

    @Test
    fun `should load models for brand`() {
        val models = vehicleMetadataService.getModelsForBrand("Audi")
        assertFalse(models.isEmpty())
        assertTrue(models.contains("A4 Avant"))
        assertTrue(models.contains("A6 Avant"))
    }

    @Test
    fun `should return empty list for non-existent brand`() {
        val models = vehicleMetadataService.getModelsForBrand("NonExistentBrand")
        assertTrue(models.isEmpty())
    }

    @Test
    fun `should return all data`() {
        val data = vehicleMetadataService.getAllBrandsWithModels()
        assertFalse(data.isEmpty())
        assertTrue(data.any { it.marka == "Audi" })
    }
}
