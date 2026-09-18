package pl.detailing.crm.product.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.product.CreateProductRequest
import pl.detailing.crm.product.ProductDraft
import pl.detailing.crm.product.ProductListItem
import pl.detailing.crm.product.ProductResponse
import pl.detailing.crm.product.ProvenanceDto
import pl.detailing.crm.product.UpdateProductRequest
import pl.detailing.crm.product.UpdateProductStudioRequest
import pl.detailing.crm.product.domain.Gtin
import pl.detailing.crm.product.domain.ProductSource
import pl.detailing.crm.product.domain.UnitOfMeasure
import pl.detailing.crm.product.domain.VerificationLevel
import pl.detailing.crm.product.infrastructure.*
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import pl.detailing.crm.shared.ValidationException
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Katalog produktów: tworzenie, edycja, lista, karta, nakładka studia i potwierdzanie
 * danych. Serce modułu.
 *
 * Governance katalogu globalnego (§2.4 architektury) egzekwuje [updateProduct]: wpisu
 * zweryfikowanego (GS1/STUDIO_CONFIRMED/CURATED) nie edytujemy in-place — zamiast tego
 * powstaje propozycja korekty. Studio, które chce inną nazwę u siebie, ma internalName.
 */
@Service
class ProductCatalogService(
    private val productRepository: ProductRepository,
    private val studioRepository: ProductStudioRepository,
    private val ratingRepository: ProductRatingRepository,
    private val noteRepository: ProductNoteRepository,
    private val proposalRepository: ProductCorrectionProposalRepository,
    private val priceResolver: ProductPriceResolver,
    private val mapper: ProductMapper,
    private val objectMapper: ObjectMapper,
    private val resolutionService: ProductResolutionService
) {

    // ── Lista ──
    @Transactional(readOnly = true)
    fun list(studioId: StudioId, filter: ProductListFilter, canSeeCosts: Boolean): List<ProductListItem> {
        val products = productRepository.search(studioId.value, filter.search.trim())
        if (products.isEmpty()) return emptyList()

        val overlays = studioRepository
            .findByStudioIdAndProductIds(studioId.value, products.map { it.id })
            .associateBy { it.productId }
        val ratings = products.mapNotNull { p ->
            ratingRepository.findByStudioIdAndProductId(studioId.value, p.id)?.let { p.id to it.rating }
        }.toMap()

        return products.asSequence()
            .map { p ->
                val overlay = overlays[p.id]
                mapper.toListItem(p, overlay, ratings[p.id], canSeeCosts)
            }
            .filter { item -> filter.onlyOurs.not() || item.isOurs }
            .filter { item -> filter.onlyFavourite.not() || item.isFavourite }
            .filter { item -> filter.includeHidden || overlays[UUID.fromString(item.id)]?.isHidden != true }
            .filter { item -> matchesRating(filter.rating, item.ratingValue) }
            .toList()
    }

    private fun matchesRating(filter: String, ratingValue: Int?): Boolean =
        when (val f = filter.trim().lowercase()) {
            "", "all" -> true
            "none" -> ratingValue == null
            else -> ratingValue != null && ratingValue == f.toIntOrNull()
        }

    /**
     * Wiersz widoczny dla tego studia: globalny albo prywatny TEGO studia.
     *
     * Wszystkie odczyty i edycje po `productId` idą przez tę funkcję. Bez niej znajomość
     * UUID-a wystarczyłaby, żeby obcy najemca przeczytał cudzy prywatny wpis — filtr na
     * liście nie broni dostępu po identyfikatorze.
     */
    private fun loadVisible(studioId: StudioId, productId: UUID): ProductEntity {
        val product = productRepository.findById(productId).orElseThrow {
            EntityNotFoundException("Produkt nie został znaleziony")
        }
        val owner = product.ownerStudioId
        if (owner != null && owner != studioId.value) {
            // Ten sam komunikat co przy braku wiersza — cudzy prywatny produkt nie ma
            // prawa zdradzić nawet tego, że istnieje.
            throw EntityNotFoundException("Produkt nie został znaleziony")
        }
        return product
    }

    // ── Karta ──
    @Transactional(readOnly = true)
    fun get(studioId: StudioId, productId: UUID, canSeeCosts: Boolean): ProductResponse {
        val product = loadVisible(studioId, productId)
        val overlay = studioRepository.findByStudioIdAndProductId(studioId.value, productId)
        val rating = ratingRepository.findByStudioIdAndProductId(studioId.value, productId)
        val noteCount = noteRepository.countActive(studioId.value, productId)
        return mapper.toResponse(product, overlay, rating, noteCount, canSeeCosts)
    }

    // ── Tworzenie ──
    @Transactional
    fun create(
        studioId: StudioId,
        userId: UserId,
        req: CreateProductRequest,
        canSeeCosts: Boolean
    ): ProductResponse {
        // WYMAGANA jest wyłącznie nazwa. Marka i opakowanie bywają nieznane przy dodawaniu
        // produktu w biegu; brak danej zapisujemy jako brak, a nie blokujemy zapisu.
        val name = req.name.trim()
        if (name.length < 2) throw ValidationException("Nazwa produktu musi mieć co najmniej 2 znaki.")
        val brand = req.brand?.trim().orEmpty()

        val unit = UnitOfMeasure.fromCode(req.unitOfMeasure) ?: UnitOfMeasure.PIECE
        val sizeUnit = UnitOfMeasure.fromCode(req.packageSizeUnit) ?: unit
        // Kolumna jest NOT NULL z ograniczeniem > 0, więc „nie podano" zapisujemy jako 1 szt.
        val sizeValue = req.packageSizeValue?.takeIf { it.isNotBlank() }?.let { parseSize(it) }
            ?: java.math.BigDecimal.ONE

        // Kod NIEPOPRAWNY traktujemy jak jego brak — `parseOrNull`, nie `parse`. Wpisanie
        // literówki nie ma blokować zapisu produktu, ma tylko odebrać mu tożsamość.
        val gtin = req.gtin?.takeIf { it.isNotBlank() }?.let { Gtin.parseOrNull(it) }

        // Bez poprawnego kodu wiersz NIE trafia do puli współdzielonej — zostaje prywatny.
        val ownerStudioId = if (gtin == null) studioId.value else null

        // Deduplikacja: kod, a gdy go nie ma — klucz naturalny w obrębie właściciela.
        val existing = when {
            gtin != null -> productRepository.findByGtin(gtin.value)
            else -> productRepository.findByNaturalKey(studioId.value, brand, name, sizeValue, sizeUnit)
        }
        if (existing != null) {
            throw ConflictException("Produkt już istnieje w katalogu (id=${existing.id}).")
        }

        val now = Instant.now()
        val product = ProductEntity(
            id = UUID.randomUUID(),
            gtin = gtin?.value,
            name = name,
            brand = brand,
            unitOfMeasure = unit,
            packageSizeValue = sizeValue,
            packageSizeUnit = sizeUnit,
            packageHeightMm = req.packageHeightMm,
            packageWidthMm = req.packageWidthMm,
            packageDepthMm = req.packageDepthMm,
            description = req.description?.trim()?.ifBlank { null },
            imageFileId = null,
            source = ProductSource.MANUAL,
            verificationLevel = VerificationLevel.UNVERIFIED,
            sourceConfidence = null,
            sourcePayload = null,
            resolvedAt = now,
            ownerStudioId = ownerStudioId,
            createdByStudioId = studioId.value,
            createdBy = userId.value,
            createdAt = now,
            updatedBy = userId.value,
            updatedAt = now
        )
        productRepository.save(product)
        gtin?.let { resolutionService.clearNegative(it) }

        // Nakładka studia od razu, gdy podano coś prywatnego (cena/dostawca/notatka).
        val overlay = upsertOverlay(studioId, userId, product.id, UpdateProductStudioRequest(
            internalName = req.internalName,
            internalNote = req.internalNote,
            price = req.price
        ), canSeeCosts)

        return mapper.toResponse(product, overlay, null, 0, canSeeCosts)
    }

    /**
     * Zapisuje kartę rozpoznaną w sieci i zwraca ją. Trafia do katalogu GLOBALNEGO tylko
     * wtedy, gdy niesie poprawny kod — inaczej zostaje wierszem prywatnym studia.
     * Wywoływane, gdy front zatwierdza wynik lookup-u. Poziom weryfikacji bierze się ze
     * źródła — nic nie awansuje samo (§3.2).
     */
    @Transactional
    fun saveResolvedDraft(
        studioId: StudioId,
        userId: UserId,
        draft: ProductDraft,
        canSeeCosts: Boolean
    ): ProductResponse {
        // Jak przy tworzeniu ręcznym: bez poprawnego kodu wiersz zostaje PRYWATNY.
        val gtin = draft.gtin?.let { Gtin.parseOrNull(it) }
        gtin?.let { productRepository.findByGtin(it.value) }?.let {
            // Wyścig: ktoś zapisał ten sam kod w międzyczasie — zwróć istniejący.
            return get(studioId, it.id, canSeeCosts)
        }
        val unit = UnitOfMeasure.fromCode(draft.unitOfMeasure) ?: UnitOfMeasure.PIECE
        val sizeUnit = UnitOfMeasure.fromCode(draft.packageSizeUnit) ?: unit
        val now = Instant.now()
        val product = ProductEntity(
            id = UUID.randomUUID(),
            gtin = gtin?.value,
            name = draft.name.trim(),
            brand = draft.brand.trim(),
            unitOfMeasure = unit,
            packageSizeValue = parseSize(draft.packageSizeValue),
            packageSizeUnit = sizeUnit,
            packageHeightMm = null,
            packageWidthMm = null,
            packageDepthMm = null,
            description = draft.description?.trim()?.ifBlank { null },
            imageFileId = null,
            source = draft.provenance.source,
            verificationLevel = draft.provenance.verificationLevel,
            sourceConfidence = draft.provenance.confidence?.let { BigDecimal.valueOf(it) },
            sourcePayload = null,
            resolvedAt = now,
            ownerStudioId = if (gtin == null) studioId.value else null,
            createdByStudioId = studioId.value,
            createdBy = userId.value,
            createdAt = now,
            updatedBy = userId.value,
            updatedAt = now
        )
        productRepository.save(product)
        gtin?.let { resolutionService.clearNegative(it) }
        return mapper.toResponse(product, null, null, 0, canSeeCosts)
    }

    // ── Edycja danych globalnych ──
    data class UpdateOutcome(val product: ProductResponse?, val proposalId: String?)

    @Transactional
    fun updateProduct(
        studioId: StudioId,
        userId: UserId,
        productId: UUID,
        req: UpdateProductRequest,
        canSeeCosts: Boolean
    ): UpdateOutcome {
        val product = loadVisible(studioId, productId)
        // Jak przy tworzeniu: wymagana jest tylko nazwa, reszta ma sensowne domyślne.
        val unit = UnitOfMeasure.fromCode(req.unitOfMeasure) ?: product.unitOfMeasure

        // Governance: wpisu zweryfikowanego NIE edytujemy in-place — powstaje propozycja.
        if (product.verificationLevel.isProtected) {
            val proposal = proposalRepository.save(ProductCorrectionProposalEntity(
                id = UUID.randomUUID(),
                productId = productId,
                studioId = studioId.value,
                proposedFields = objectMapper.writeValueAsString(req),
                reason = null,
                createdBy = userId.value
            ))
            return UpdateOutcome(product = null, proposalId = proposal.id.toString())
        }

        product.name = req.name.trim()
        product.brand = req.brand?.trim().orEmpty()
        product.unitOfMeasure = unit
        product.packageSizeUnit = UnitOfMeasure.fromCode(req.packageSizeUnit) ?: unit
        product.packageSizeValue = req.packageSizeValue?.takeIf { it.isNotBlank() }?.let { parseSize(it) }
            ?: product.packageSizeValue
        product.packageHeightMm = req.packageHeightMm
        product.packageWidthMm = req.packageWidthMm
        product.packageDepthMm = req.packageDepthMm
        product.description = req.description?.trim()?.ifBlank { null }
        product.updatedBy = userId.value
        product.updatedAt = Instant.now()
        productRepository.save(product)

        val overlay = studioRepository.findByStudioIdAndProductId(studioId.value, productId)
        val rating = ratingRepository.findByStudioIdAndProductId(studioId.value, productId)
        val noteCount = noteRepository.countActive(studioId.value, productId)
        return UpdateOutcome(mapper.toResponse(product, overlay, rating, noteCount, canSeeCosts), null)
    }

    // ── Nakładka studia ──
    @Transactional
    fun updateStudioOverlay(
        studioId: StudioId,
        userId: UserId,
        productId: UUID,
        req: UpdateProductStudioRequest,
        canSeeCosts: Boolean
    ): ProductResponse {
        val product = loadVisible(studioId, productId)
        val overlay = upsertOverlay(studioId, userId, productId, req, canSeeCosts)
        val rating = ratingRepository.findByStudioIdAndProductId(studioId.value, productId)
        val noteCount = noteRepository.countActive(studioId.value, productId)
        return mapper.toResponse(product, overlay, rating, noteCount, canSeeCosts)
    }

    // ── Potwierdzenie zgodności z etykietą → STUDIO_CONFIRMED ──
    @Transactional
    fun confirm(studioId: StudioId, userId: UserId, productId: UUID, canSeeCosts: Boolean): ProductResponse {
        val product = loadVisible(studioId, productId)
        // Awans TYLKO w górę i tylko z akcji człowieka. CURATED/GS1 zostają jak są.
        if (product.verificationLevel == VerificationLevel.UNVERIFIED ||
            product.verificationLevel == VerificationLevel.AI_SUGGESTED
        ) {
            product.verificationLevel = VerificationLevel.STUDIO_CONFIRMED
            product.updatedBy = userId.value
            product.updatedAt = Instant.now()
            productRepository.save(product)
        }
        return get(studioId, productId, canSeeCosts)
    }

    // ── helpers ──
    private fun upsertOverlay(
        studioId: StudioId,
        userId: UserId,
        productId: UUID,
        req: UpdateProductStudioRequest,
        canSeeCosts: Boolean
    ): ProductStudioEntity {
        val existing = studioRepository.findByStudioIdAndProductId(studioId.value, productId)
        val now = Instant.now()

        // Cenę wolno ustawić tylko z PRODUCTS_COSTS. Bez uprawnienia zostaje poprzednia.
        val price = if (canSeeCosts) priceResolver.resolve(req.price) else null
        val keepPrice = !canSeeCosts

        val entity = existing ?: ProductStudioEntity(
            id = UUID.randomUUID(),
            studioId = studioId.value,
            productId = productId,
            unitPriceNetCents = null,
            unitPriceGrossCents = null,
            priceEnteredAs = null,
            vatRate = null,
            internalName = null,
            internalNote = null,
            createdBy = userId.value,
            updatedBy = userId.value,
            createdAt = now,
            updatedAt = now
        )
        entity.internalName = req.internalName?.trim()?.ifBlank { null }
        entity.internalNote = req.internalNote?.trim()?.ifBlank { null }
        entity.isFavourite = req.isFavourite
        entity.isHidden = req.isHidden
        if (!keepPrice) {
            entity.unitPriceNetCents = price?.netCents
            entity.unitPriceGrossCents = price?.grossCents
            entity.priceEnteredAs = price?.enteredAs
            entity.vatRate = price?.vatRate
        }
        entity.updatedBy = userId.value
        entity.updatedAt = now
        return studioRepository.save(entity)
    }

    private fun parseSize(raw: String): BigDecimal {
        val value = runCatching { BigDecimal(raw.trim().replace(",", ".")) }.getOrNull()
            ?: throw ValidationException("Wielkość opakowania musi być liczbą.")
        if (value <= BigDecimal.ZERO) throw ValidationException("Wielkość opakowania musi być dodatnia.")
        return value
    }
}

data class ProductListFilter(
    val search: String = "",
    val onlyOurs: Boolean = false,
    val onlyFavourite: Boolean = false,
    val includeHidden: Boolean = false,
    /**
     * Ocena zespołu: "1".."5" (dokładnie tyle gwiazdek), "none" (bez oceny) albo puste
     * = bez filtrowania. Tekst, nie Int?, bo „bez oceny" i „nie filtruj" to DWA różne
     * stany, a jeden `Int?` potrafi wyrazić tylko jeden z nich.
     */
    val rating: String = ""
)
