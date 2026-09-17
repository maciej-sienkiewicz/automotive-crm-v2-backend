package pl.detailing.crm.product.usage

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.subscription.entitlement.capability.CapabilityKey
import pl.detailing.crm.subscription.entitlement.capability.RequiresCapability
import java.util.UUID

/**
 * Powiązania produktów z wizytą — relacja wiele-do-wielu, czysto informacyjna.
 *
 * Osobny kontroler zamiast kolejnych metod w VisitController (ten ma już ~25 endpointów)
 * — dokładnie tak, jak wydzielono mapę uszkodzeń. Za PRODUCTS_ACCESS (moduł kupiony);
 * odczyt wymaga PRODUCTS_VIEW + VISITS_VIEW, zapis PRODUCTS_USAGE (które implikuje
 * VISITS_VIEW przez katalog uprawnień).
 */
@RestController
@RequestMapping("/api/visits/{visitId}/products")
@RequiresCapability(CapabilityKey.PRODUCTS_ACCESS)
class VisitProductsController(
    private val visitProductService: VisitProductService
) {
    @GetMapping
    @RequiresPermission(Permission.PRODUCTS_VIEW)
    fun list(@PathVariable visitId: String): ResponseEntity<List<VisitProductDto>> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(visitProductService.listForVisit(principal.studioId, UUID.fromString(visitId)))
    }

    @PostMapping
    @RequiresPermission(Permission.PRODUCTS_USAGE)
    fun link(@PathVariable visitId: String, @RequestBody req: LinkProductRequest): ResponseEntity<VisitProductDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        val dto = visitProductService.link(
            principal.studioId, principal.userId, principal.fullName,
            UUID.fromString(visitId), UUID.fromString(req.productId), req.note
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(dto)
    }

    @PatchMapping("/{linkId}")
    @RequiresPermission(Permission.PRODUCTS_USAGE)
    fun updateNote(
        @PathVariable visitId: String,
        @PathVariable linkId: String,
        @RequestBody req: UpdateLinkNoteRequest
    ): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        visitProductService.updateNote(principal.studioId, UUID.fromString(linkId), req.note)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{linkId}")
    @RequiresPermission(Permission.PRODUCTS_USAGE)
    fun unlink(@PathVariable visitId: String, @PathVariable linkId: String): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        visitProductService.unlink(principal.studioId, UUID.fromString(linkId))
        return ResponseEntity.noContent().build()
    }
}

/**
 * Wizyty tego studia, na których użyto produktu — sekcja „Gdzie używaliśmy" w karcie
 * produktu. Osobna ścieżka, bo wisi pod /products, nie pod /visits.
 */
@RestController
@RequestMapping("/api/v1/products/{productId}/visits")
@RequiresCapability(CapabilityKey.PRODUCTS_ACCESS)
class ProductVisitsController(
    private val visitProductService: VisitProductService
) {
    @GetMapping
    @RequiresPermission(Permission.PRODUCTS_VIEW)
    fun list(
        @PathVariable productId: String,
        @RequestParam(required = false, defaultValue = "") search: String,
        @RequestParam(required = false, defaultValue = "1") page: Int,
        @RequestParam(required = false, defaultValue = "10") limit: Int
    ): ResponseEntity<ProductVisitUsagePage> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(
            visitProductService.listVisitsForProduct(
                principal.studioId, UUID.fromString(productId), search, page, limit
            )
        )
    }
}

data class LinkProductRequest(val productId: String, val note: String? = null)
data class UpdateLinkNoteRequest(val note: String? = null)
