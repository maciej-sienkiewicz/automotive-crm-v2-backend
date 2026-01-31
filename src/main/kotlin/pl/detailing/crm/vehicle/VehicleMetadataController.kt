package pl.detailing.crm.vehicle

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/vehicle-metadata")
class VehicleMetadataController(
    private val vehicleMetadataService: VehicleMetadataService
) {

    @GetMapping
    fun getAllMetadata(): ResponseEntity<List<BrandWithModels>> {
        return ResponseEntity.ok(vehicleMetadataService.getAllBrandsWithModels())
    }

    @GetMapping("/brands")
    fun getBrands(): ResponseEntity<List<String>> {
        return ResponseEntity.ok(vehicleMetadataService.getBrands())
    }

    @GetMapping("/brands/{brand}/models")
    fun getModels(@PathVariable brand: String): ResponseEntity<List<String>> {
        val models = vehicleMetadataService.getModelsForBrand(brand)
        return ResponseEntity.ok(models)
    }
}
