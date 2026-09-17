package pl.detailing.crm.product.application

import org.springframework.stereotype.Component
import pl.detailing.crm.product.ProductListItem
import pl.detailing.crm.product.ProductPriceDto
import pl.detailing.crm.product.ProductRatingDto
import pl.detailing.crm.product.ProductResponse
import pl.detailing.crm.product.ProvenanceDto
import pl.detailing.crm.product.infrastructure.ProductEntity
import pl.detailing.crm.product.infrastructure.ProductRatingEntity
import pl.detailing.crm.product.infrastructure.ProductStudioEntity

/**
 * Zamienia encje na odpowiedzi API. Jedno miejsce, w którym decyduje się widoczność
 * ceny: gdy [canSeeCosts] jest false, pola cenowe NIE powstają — serwer nie wypuszcza
 * kwoty, której odbiorca nie ma prawa zobaczyć (uprawnienie PRODUCTS_COSTS).
 *
 * Uwaga na regułę z CLAUDE.md §1: cena jest tylko przepisywana z encji, gdzie leży
 * dokładnie tak, jak wpisał ją człowiek (netto, brutto, kierunek). NIC tu nie liczymy.
 */
@Component
class ProductMapper {

    fun toResponse(
        product: ProductEntity,
        studio: ProductStudioEntity?,
        rating: ProductRatingEntity?,
        noteCount: Long,
        canSeeCosts: Boolean
    ): ProductResponse = ProductResponse(
        id = product.id.toString(),
        gtin = product.gtin,
        name = product.name,
        brand = product.brand,
        unitOfMeasure = product.unitOfMeasure.name,
        packageSizeValue = product.packageSizeValue.stripTrailingZeros().toPlainString(),
        packageSizeUnit = product.packageSizeUnit.name,
        packageHeightMm = product.packageHeightMm,
        packageWidthMm = product.packageWidthMm,
        packageDepthMm = product.packageDepthMm,
        description = product.description,
        imageFileId = product.imageFileId,
        provenance = ProvenanceDto(
            source = product.source,
            verificationLevel = product.verificationLevel,
            confidence = product.sourceConfidence?.toDouble()
        ),
        isPrivate = product.ownerStudioId != null,
        internalName = studio?.internalName,
        internalNote = studio?.internalNote,
        isFavourite = studio?.isFavourite ?: false,
        isHidden = studio?.isHidden ?: false,
        price = if (canSeeCosts) toPrice(studio) else null,
        rating = rating?.let {
            ProductRatingDto(it.rating, it.justification, it.ratedByName, it.ratedAt)
        },
        noteCount = noteCount,
        isEditableInPlace = !product.verificationLevel.isProtected,
        createdAt = product.createdAt,
        updatedAt = product.updatedAt
    )

    fun toListItem(
        product: ProductEntity,
        studio: ProductStudioEntity?,
        ratingValue: Int?,
        canSeeCosts: Boolean
    ): ProductListItem = ProductListItem(
        id = product.id.toString(),
        gtin = product.gtin,
        name = product.name,
        brand = product.brand,
        unitOfMeasure = product.unitOfMeasure.name,
        packageSizeValue = product.packageSizeValue.stripTrailingZeros().toPlainString(),
        packageSizeUnit = product.packageSizeUnit.name,
        imageFileId = product.imageFileId,
        verificationLevel = product.verificationLevel,
        isFavourite = studio?.isFavourite ?: false,
        isOurs = studio != null,
        price = if (canSeeCosts) toPrice(studio) else null,
        ratingValue = ratingValue
    )

    private fun toPrice(studio: ProductStudioEntity?): ProductPriceDto? {
        if (studio == null || !studio.hasPrice) return null
        return ProductPriceDto(
            unitPriceNet = studio.unitPriceNetCents!!,
            unitPriceGross = studio.unitPriceGrossCents!!,
            priceEnteredAs = studio.priceEnteredAs!!,
            vatRate = studio.vatRate!!
        )
    }
}
