package pl.detailing.crm.product

import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.product.adapter.ai.BarcodeImageExtractionService
import pl.detailing.crm.product.application.ProductCatalogService
import pl.detailing.crm.product.application.ProductListFilter
import pl.detailing.crm.product.application.ProductResolutionService
import pl.detailing.crm.product.application.ProductResolution
import pl.detailing.crm.product.notes.NoteDto
import pl.detailing.crm.product.notes.ProductNoteService
import pl.detailing.crm.product.rating.ProductRatingService
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.rolepreview.RolePreviewOutboundGuard
import pl.detailing.crm.rolepreview.SimulatedEffectChannel
import pl.detailing.crm.role.permission.PermissionCheckService
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.shared.Pagination
import pl.detailing.crm.subscription.entitlement.capability.CapabilityKey
import pl.detailing.crm.subscription.entitlement.capability.RequiresCapability
import java.util.UUID
import pl.detailing.crm.product.application.ProductListSort

/**
 * Moduł produktów. Cały kontroler jest za [CapabilityKey.PRODUCTS_ACCESS] (co studio
 * kupiło) i za uprawnieniami roli (co wolno człowiekowi). Cena jednostkowa jest
 * dodatkowo gated przez PRODUCTS_COSTS — sprawdzane inline, bo to samo pole steruje
 * i zapisem, i widocznością w odpowiedzi.
 */
@RestController
@RequestMapping("/api/v1/products")
@RequiresCapability(CapabilityKey.PRODUCTS_ACCESS)
class ProductController(
    private val catalogService: ProductCatalogService,
    private val resolutionService: ProductResolutionService,
    private val barcodeImageExtractionService: BarcodeImageExtractionService,
    private val noteService: ProductNoteService,
    private val ratingService: ProductRatingService,
    private val permissionCheckService: PermissionCheckService,
    private val rolePreviewGuard: RolePreviewOutboundGuard
) {
    private fun canSeeCosts(): Boolean {
        val p = SecurityContextHelper.getCurrentUser()
        return permissionCheckService.hasPermission(p.userId, p.studioId, Permission.PRODUCTS_COSTS)
    }

    // ── Lista ──
    @GetMapping
    @RequiresPermission(Permission.PRODUCTS_VIEW)
    fun list(
        @RequestParam(required = false, defaultValue = "") search: String,
        @RequestParam(required = false, defaultValue = "false") onlyFavourite: Boolean,
        @RequestParam(required = false, defaultValue = "false") includeHidden: Boolean,
        @RequestParam(required = false, defaultValue = "") rating: String,
        @RequestParam(required = false, defaultValue = "1") page: Int,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
        @RequestParam(required = false) sortBy: String?,
        @RequestParam(required = false, defaultValue = "asc") sortDirection: String
    ): ResponseEntity<ProductListResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        var items = catalogService.list(
            principal.studioId,
            ProductListFilter(search, onlyFavourite, includeHidden, rating),
            canSeeCosts()
        )
        items = ProductListSort.apply(items, sortBy, sortDirection)
        val total = items.size
        val safePage = Pagination.normalizePage(page)
        val safeLimit = Pagination.normalizeLimit(limit, max = 200)
        val slice = Pagination.slice(items, safePage, safeLimit)
        return ResponseEntity.ok(
            ProductListResponse(
                products = slice,
                pagination = ProductPaginationInfo(
                    currentPage = safePage,
                    totalPages = Pagination.totalPages(total, safeLimit),
                    totalItems = total,
                    itemsPerPage = safeLimit
                )
            )
        )
    }

    // ── Karta ──
    @GetMapping("/{id}")
    @RequiresPermission(Permission.PRODUCTS_VIEW)
    fun get(@PathVariable id: String): ResponseEntity<ProductResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(catalogService.get(principal.studioId, UUID.fromString(id), canSeeCosts()))
    }

    // ── Tworzenie ──
    @PostMapping
    @RequiresPermission(Permission.PRODUCTS_MANAGE)
    fun create(@RequestBody req: CreateProductRequest): ResponseEntity<ProductResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val created = catalogService.create(principal.studioId, principal.userId, req, canSeeCosts())
        return ResponseEntity.status(HttpStatus.CREATED).body(created)
    }

    // ── Rozpoznanie po kodzie ──
    @PostMapping("/lookup")
    @RequiresPermission(Permission.PRODUCTS_MANAGE)
    fun lookup(@RequestBody req: LookupRequest): ResponseEntity<LookupResponse> = runBlocking {
        val principal = SecurityContextHelper.getCurrentUser()
        // Piaskownica podglądu roli szuka tylko w naszym katalogu - do sieci (wyszukiwarka AI)
        // nie pyta. Brak w katalogu zapisujemy w panelu podglądu jako „system szukałby w sieci".
        val resolution = if (rolePreviewGuard.isSandbox(principal.studioId.value)) {
            resolutionService.resolveLocally(req.barcode).also {
                if (it.status == ProductResolution.Status.NOT_FOUND) {
                    rolePreviewGuard.intercepts(
                        principal.studioId.value, SimulatedEffectChannel.WEB_SEARCH, null,
                        "Wyszukanie w sieci produktu o kodzie ${req.barcode.trim()}"
                    )
                }
            }
        } else {
            resolutionService.resolve(req.barcode)
        }
        val response = when (resolution.status) {
            ProductResolution.Status.FOUND_LOCAL -> {
                // Produkt siedzi w cache — oddaj pełną kartę i zapisz, że to studio też go ma.
                // Człowiek właśnie zeskanował kod w oknie „Dodaj produkt", więc to jest dodanie
                // do katalogu, nawet jeśli wiersz założył ktoś inny.
                val localId = resolveLocalId(resolution)
                catalogService.adopt(principal.studioId, principal.userId, localId)
                val existing = catalogService.get(principal.studioId, localId, canSeeCosts())
                LookupResponse("FOUND_LOCAL", existing, null, existing.provenance)
            }
            ProductResolution.Status.RESOLVED -> {
                val r = resolution.result!!
                val prov = resolution.provenance()!!
                val draft = ProductDraft(
                    gtin = r.spec.gtin,
                    name = r.spec.name,
                    brand = r.spec.brand,
                    unitOfMeasure = r.spec.unitOfMeasure.name,
                    packageSizeValue = r.spec.packageSizeValue.stripTrailingZeros().toPlainString(),
                    packageSizeUnit = r.spec.packageSizeUnit.name,
                    description = r.spec.description,
                    provenance = ProvenanceDto(prov.source, prov.verificationLevel, prov.confidence),
                    sourceUrl = r.rawPayload
                )
                LookupResponse("RESOLVED", null, draft, draft.provenance)
            }
            ProductResolution.Status.NOT_FOUND ->
                LookupResponse("NOT_FOUND", null, null, null)
        }
        ResponseEntity.ok(response)
    }

    private fun resolveLocalId(resolution: ProductResolution): UUID {
        // FOUND_LOCAL zawsze niesie spec z gtin. Szukamy wprost po kodzie, nie przez listę:
        // lista pokazuje wyłącznie produkty tego studia, a tu chodzi o cały cache — to jest
        // dokładnie ta jedna ścieżka, do której współdzielona tabela służy.
        val gtin = resolution.result!!.spec.gtin!!
        return catalogService.findByGtin(gtin)
            ?: throw pl.detailing.crm.shared.EntityNotFoundException("Produkt zniknął z katalogu")
    }

    /**
     * Odczyt cyfr kodu ZE ZDJĘCIA (zapas, gdy dekoder w przeglądarce nie odczyta kadru).
     * Zwraca sam GTIN — front woła potem zwykły lookup, żeby ścieżka rozpoznania była
     * jedna. Wzorzec 1:1 z `POST /batch-orders/vin/extract`.
     */
    @PostMapping("/barcode/extract", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @RequiresPermission(Permission.PRODUCTS_MANAGE)
    fun extractBarcode(@RequestParam("image") image: MultipartFile): ResponseEntity<BarcodeExtractResponse> = runBlocking {
        rolePreviewGuard.requireOutsideSandbox(
            SecurityContextHelper.getCurrentUser().studioId.value, SimulatedEffectChannel.AI,
            "zdjęcie trafiłoby do modelu AI, który odczytałby z niego kod kreskowy"
        )
        if (image.isEmpty || image.size > MAX_BARCODE_IMAGE_BYTES) {
            return@runBlocking ResponseEntity.badRequest().body(BarcodeExtractResponse(null))
        }
        val contentType = image.contentType?.takeIf { it.startsWith("image/") }
            ?: return@runBlocking ResponseEntity.badRequest().body(BarcodeExtractResponse(null))
        val gtin = barcodeImageExtractionService.extractGtin(image.bytes, contentType)
        ResponseEntity.ok(BarcodeExtractResponse(gtin?.value))
    }

    /** Zapis karty rozpoznanej zewnętrznie do katalogu (front zatwierdza wynik lookup-u). */
    @PostMapping("/from-draft")
    @RequiresPermission(Permission.PRODUCTS_MANAGE)
    fun createFromDraft(@RequestBody draft: ProductDraft): ResponseEntity<ProductResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val saved = catalogService.saveResolvedDraft(principal.studioId, principal.userId, draft, canSeeCosts())
        return ResponseEntity.status(HttpStatus.CREATED).body(saved)
    }

    // ── Edycja danych globalnych (in-place albo propozycja korekty) ──
    @PatchMapping("/{id}")
    @RequiresPermission(Permission.PRODUCTS_MANAGE)
    fun update(@PathVariable id: String, @RequestBody req: UpdateProductRequest): ResponseEntity<Any> {
        val principal = SecurityContextHelper.getCurrentUser()
        val outcome = catalogService.updateProduct(principal.studioId, principal.userId, UUID.fromString(id), req, canSeeCosts())
        return if (outcome.product != null) {
            ResponseEntity.ok(outcome.product)
        } else {
            // Wpis zweryfikowany — zamiast edycji in-place powstała propozycja korekty.
            ResponseEntity.status(HttpStatus.ACCEPTED).body(
                mapOf(
                    "status" to "PROPOSAL_CREATED",
                    "proposalId" to outcome.proposalId,
                    "message" to "Ten produkt jest zweryfikowany. Zmiana trafiła do weryfikacji; " +
                        "u siebie możesz nadać własną nazwę w polu „nazwa własna”."
                )
            )
        }
    }

    // ── Nakładka studia ──
    @PutMapping("/{id}/studio")
    @RequiresPermission(Permission.PRODUCTS_MANAGE)
    fun updateStudio(@PathVariable id: String, @RequestBody req: UpdateProductStudioRequest): ResponseEntity<ProductResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        val result = catalogService.updateStudioOverlay(principal.studioId, principal.userId, UUID.fromString(id), req, canSeeCosts())
        return ResponseEntity.ok(result)
    }

    // ── Notatki ──
    @GetMapping("/{id}/notes")
    @RequiresPermission(Permission.PRODUCTS_VIEW)
    fun listNotes(@PathVariable id: String): ResponseEntity<List<NoteDto>> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(noteService.list(principal.studioId, UUID.fromString(id)))
    }

    @PostMapping("/{id}/notes")
    @RequiresPermission(Permission.PRODUCTS_MANAGE)
    fun addNote(@PathVariable id: String, @RequestBody req: AddNoteRequest): ResponseEntity<NoteDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        val note = noteService.add(
            principal.studioId, principal.userId, principal.fullName,
            UUID.fromString(id), req.content, req.visitId?.let(UUID::fromString)
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(note)
    }

    @PatchMapping("/{id}/notes/{noteId}")
    @RequiresPermission(Permission.PRODUCTS_MANAGE)
    fun editNote(@PathVariable id: String, @PathVariable noteId: String, @RequestBody req: AddNoteRequest): ResponseEntity<NoteDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(
            noteService.edit(principal.studioId, principal.userId, principal.fullName, principal.isOwner, UUID.fromString(noteId), req.content)
        )
    }

    @DeleteMapping("/{id}/notes/{noteId}")
    @RequiresPermission(Permission.PRODUCTS_MANAGE)
    fun deleteNote(@PathVariable id: String, @PathVariable noteId: String): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        noteService.delete(principal.studioId, principal.userId, principal.isOwner, UUID.fromString(noteId))
        return ResponseEntity.noContent().build()
    }

    // ── Ocena ──
    @PutMapping("/{id}/rating")
    @RequiresPermission(Permission.PRODUCTS_MANAGE)
    fun setRating(@PathVariable id: String, @RequestBody req: SetRatingRequest): ResponseEntity<ProductRatingDto> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(
            ratingService.set(principal.studioId, principal.userId, principal.fullName, UUID.fromString(id), req.rating, req.justification)
        )
    }

    @DeleteMapping("/{id}/rating")
    @RequiresPermission(Permission.PRODUCTS_MANAGE)
    fun clearRating(@PathVariable id: String): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        ratingService.clear(principal.studioId, UUID.fromString(id))
        return ResponseEntity.noContent().build()
    }
}

data class ProductListResponse(
    val products: List<ProductListItem>,
    val pagination: ProductPaginationInfo
)

data class ProductPaginationInfo(
    val currentPage: Int,
    val totalPages: Int,
    val totalItems: Int,
    val itemsPerPage: Int
)

data class AddNoteRequest(val content: String, val visitId: String? = null)
data class SetRatingRequest(val rating: Int, val justification: String? = null)
data class BarcodeExtractResponse(val gtin: String?)

/** Front skaluje zdjęcie do ~1600 px przed wysyłką; 8 MB to zapas na telefon bez skalowania. */
const val MAX_BARCODE_IMAGE_BYTES: Long = 8L * 1024 * 1024
