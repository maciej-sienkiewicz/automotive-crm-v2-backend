package pl.detailing.crm.product

import pl.detailing.crm.product.domain.ProductSource
import pl.detailing.crm.product.domain.VerificationLevel
import java.time.Instant

/**
 * Kształty żądań i odpowiedzi modułu produktów. Wszystkie kwoty w GROSZACH (integer),
 * zgodnie z `docs/pricing-api-contract.md` §1.1.
 *
 * Pola cenowe (unitPrice*) są w odpowiedzi WYŁĄCZNIE, gdy odbiorca ma PRODUCTS_COSTS —
 * odcinamy je na poziomie mapowania ([ProductMapper]), nie na froncie: serwer nie
 * wypuszcza ceny, której odbiorca nie ma prawa zobaczyć.
 */

// ── Pochodzenie ──
data class ProvenanceDto(
    val source: ProductSource,
    val verificationLevel: VerificationLevel,
    val confidence: Double?
)

// ── Cena jednostkowa (informacyjna) ──
data class ProductPriceDto(
    val unitPriceNet: Long,
    val unitPriceGross: Long,
    val priceEnteredAs: String,   // NET | GROSS
    val vatRate: Int
)

// ── Karta produktu ──
data class ProductResponse(
    val id: String,
    val gtin: String?,
    val name: String,
    val brand: String,
    val unitOfMeasure: String,
    val packageSizeValue: String,
    val packageSizeUnit: String,
    val packageHeightMm: Int?,
    val packageWidthMm: Int?,
    val packageDepthMm: Int?,
    val description: String?,
    val imageFileId: String?,
    val provenance: ProvenanceDto,
    /**
     * true = wiersz PRYWATNY tego studia (bez poprawnego kodu kreskowego), więc nie widzi
     * go nikt inny. Front tłumaczy to człowiekowi — inaczej „czemu tego nie ma u kolegi"
     * jest zagadką.
     */
    val isPrivate: Boolean,
    // ── Nakładka studia ──
    val internalName: String?,
    val internalNote: String?,
    val isFavourite: Boolean,
    val isHidden: Boolean,
    val price: ProductPriceDto?,       // null bez PRODUCTS_COSTS lub gdy studio nie podało ceny
    val rating: ProductRatingDto?,
    val noteCount: Long,
    val isEditableInPlace: Boolean,    // false dla wpisów zweryfikowanych → PATCH tworzy propozycję
    val createdAt: Instant,
    val updatedAt: Instant
)

data class ProductListItem(
    val id: String,
    val gtin: String?,
    val name: String,
    val brand: String,
    val unitOfMeasure: String,
    val packageSizeValue: String,
    val packageSizeUnit: String,
    val imageFileId: String?,
    val verificationLevel: VerificationLevel,
    val isFavourite: Boolean,
    val isOurs: Boolean,               // czy studio ma na to nakładkę
    val price: ProductPriceDto?,       // null bez PRODUCTS_COSTS
    val ratingValue: Int?
)

data class ProductRatingDto(
    val rating: Int,
    val justification: String?,
    val ratedByName: String,
    val ratedAt: Instant
)

// ── Żądania ──
data class CreateProductRequest(
    val gtin: String?,
    val name: String,
    // Jedynym wymaganym polem jest nazwa. Reszta bywa nieznana w chwili dodawania
    // (ktoś wpisuje produkt w biegu) i można ją uzupełnić później.
    val brand: String? = null,
    val unitOfMeasure: String? = null,
    val packageSizeValue: String? = null,
    val packageSizeUnit: String? = null,
    val packageHeightMm: Int? = null,
    val packageWidthMm: Int? = null,
    val packageDepthMm: Int? = null,
    val description: String? = null,
    // Nakładka studia — opcjonalna przy tworzeniu.
    val internalName: String? = null,
    val internalNote: String? = null,
    val price: PriceInput? = null
)

data class PriceInput(
    val unitPriceNet: Long?,
    val unitPriceGross: Long?,
    val priceEnteredAs: String,   // NET | GROSS — kierunek, którego wpisał człowiek
    val vatRate: Int
)

data class UpdateProductRequest(
    val name: String,
    val brand: String? = null,
    val unitOfMeasure: String? = null,
    val packageSizeValue: String? = null,
    val packageSizeUnit: String? = null,
    val packageHeightMm: Int? = null,
    val packageWidthMm: Int? = null,
    val packageDepthMm: Int? = null,
    val description: String? = null
)

data class UpdateProductStudioRequest(
    val internalName: String? = null,
    val internalNote: String? = null,
    val isFavourite: Boolean = false,
    val isHidden: Boolean = false,
    val price: PriceInput? = null      // wymaga PRODUCTS_COSTS
)

data class LookupRequest(val barcode: String)

data class LookupResponse(
    val status: String,                // FOUND_LOCAL | RESOLVED | NOT_FOUND
    val product: ProductResponse?,     // FOUND_LOCAL: pełna karta z katalogu
    val draft: ProductDraft?,          // RESOLVED: karta do zatwierdzenia (jeszcze nie zapisana u studia)
    val provenance: ProvenanceDto?
)

/** Wstępnie wypełniony formularz po rozpoznaniu zewnętrznym — NIE jest jeszcze w katalogu studia. */
data class ProductDraft(
    val gtin: String?,
    val name: String,
    val brand: String,
    val unitOfMeasure: String,
    val packageSizeValue: String,
    val packageSizeUnit: String,
    val description: String?,
    val provenance: ProvenanceDto,
    /**
     * Adres źródła, z którego pochodzi rozpoznanie. Dokumentacja OpenAI wymaga, żeby
     * źródła pokazane użytkownikowi były WIDOCZNE I KLIKALNE — dlatego cytowanie idzie
     * aż do formularza, a nie kończy się w logu.
     */
    val sourceUrl: String? = null
)
