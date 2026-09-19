package pl.detailing.crm.product.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import pl.detailing.crm.product.ProductListItem
import pl.detailing.crm.product.domain.VerificationLevel
import java.time.Instant
import java.util.UUID

/**
 * Kolejność listy produktów. Sedno: sortowanie po liczbie musi być POWTARZALNE -
 * przy katalogu, w którym połowa pozycji ma zero użyć, remis rozstrzygnięty
 * „kolejnością z bazy" sprawia, że lista skacze przy każdym odświeżeniu.
 */
class ProductListSortTest {

    private fun item(
        name: String,
        usageCount: Int = 0,
        ratingValue: Int? = null,
        brand: String = "Marka"
    ) = ProductListItem(
        id = UUID.randomUUID().toString(),
        gtin = null,
        name = name,
        brand = brand,
        unitOfMeasure = "PIECE",
        packageSizeValue = "500",
        packageSizeUnit = "MILLILITER",
        imageFileId = null,
        verificationLevel = VerificationLevel.UNVERIFIED,
        isFavourite = false,
        isOurs = true,
        price = null,
        ratingValue = ratingValue,
        usageCount = usageCount,
        lastUsedAt = if (usageCount > 0) Instant.now() else null
    )

    private fun names(items: List<ProductListItem>) = items.map { it.name }

    @Test
    fun `po uzyciu malejaco - najczesciej uzywane na gorze`() {
        val sorted = ProductListSort.apply(
            listOf(item("Wosk", 4), item("Ceramika", 37), item("Pasta", 0)),
            "usage",
            "desc"
        )
        assertEquals(listOf("Ceramika", "Wosk", "Pasta"), names(sorted))
    }

    @Test
    fun `po uzyciu rosnaco - lezaki na gorze`() {
        val sorted = ProductListSort.apply(
            listOf(item("Wosk", 4), item("Ceramika", 37), item("Pasta", 0)),
            "usage",
            "asc"
        )
        assertEquals(listOf("Pasta", "Wosk", "Ceramika"), names(sorted))
    }

    @Test
    fun `remis w uzyciu rozstrzyga nazwa, w obu kierunkach`() {
        val tied = listOf(item("Zeta", 0), item("Alfa", 0), item("Beta", 0))

        assertEquals(listOf("Alfa", "Beta", "Zeta"), names(ProductListSort.apply(tied, "usage", "desc")))
        assertEquals(listOf("Alfa", "Beta", "Zeta"), names(ProductListSort.apply(tied, "usage", "asc")))
    }

    @Test
    fun `produkt bez oceny spada na koniec niezaleznie od kierunku`() {
        val items = listOf(item("Bez oceny"), item("Slaby", ratingValue = 2), item("Dobry", ratingValue = 5))

        assertEquals(listOf("Dobry", "Slaby", "Bez oceny"), names(ProductListSort.apply(items, "rating", "desc")))
        assertEquals(listOf("Slaby", "Dobry", "Bez oceny"), names(ProductListSort.apply(items, "rating", "asc")))
    }

    @Test
    fun `nazwa sortuje bez wzgledu na wielkosc liter`() {
        val sorted = ProductListSort.apply(listOf(item("wosk"), item("Ceramika"), item("Pasta")), "name", "asc")
        assertEquals(listOf("Ceramika", "Pasta", "wosk"), names(sorted))
    }

    @Test
    fun `nieznany i brakujacy klucz daja porzadek alfabetyczny`() {
        val items = listOf(item("Wosk"), item("Ceramika"))

        assertEquals(listOf("Ceramika", "Wosk"), names(ProductListSort.apply(items, null, "asc")))
        assertEquals(listOf("Ceramika", "Wosk"), names(ProductListSort.apply(items, "cokolwiek", "desc")))
    }
}
