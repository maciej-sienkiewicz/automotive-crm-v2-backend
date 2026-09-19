package pl.detailing.crm.product.application

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import pl.detailing.crm.product.ProductListItem
import pl.detailing.crm.product.domain.VerificationLevel
import pl.detailing.crm.product.infrastructure.ProductUsageRow
import java.time.Instant
import java.util.UUID

/**
 * Doklejanie użycia do wiersza listy. Sedno: produkt, którego nikt nie użył, ma
 * pokazać ZERO - a nie pustkę, która na ekranie wygląda jak brak danych.
 */
class ProductUsageMergeTest {

    private fun item(id: UUID, name: String = "Wosk") = ProductListItem(
        id = id.toString(),
        gtin = null,
        name = name,
        brand = "Marka",
        unitOfMeasure = "PIECE",
        packageSizeValue = "500",
        packageSizeUnit = "MILLILITER",
        imageFileId = null,
        verificationLevel = VerificationLevel.UNVERIFIED,
        isFavourite = false,
        isOurs = true,
        price = null,
        ratingValue = null
    )

    @Test
    fun `uzycie trafia do wlasciwego wiersza`() {
        val used = UUID.randomUUID()
        val untouched = UUID.randomUUID()
        val lastUse = Instant.parse("2026-05-14T10:15:00Z")

        val merged = ProductUsageMerge.apply(
            listOf(item(used, "Ceramika"), item(untouched, "Wosk")),
            listOf(ProductUsageRow(used, 12L, lastUse))
        )

        assertEquals(12, merged[0].usageCount)
        assertEquals(lastUse, merged[0].lastUsedAt)
        assertEquals(0, merged[1].usageCount)
        assertNull(merged[1].lastUsedAt)
    }

    @Test
    fun `brak agregatu zostawia zera na calej liscie`() {
        val merged = ProductUsageMerge.apply(listOf(item(UUID.randomUUID())), emptyList())

        assertEquals(0, merged.single().usageCount)
        assertNull(merged.single().lastUsedAt)
    }

    @Test
    fun `wiersz agregatu bez pary na liscie niczego nie psuje`() {
        val onList = UUID.randomUUID()
        val merged = ProductUsageMerge.apply(
            listOf(item(onList)),
            listOf(ProductUsageRow(UUID.randomUUID(), 5L, Instant.now()))
        )

        assertEquals(0, merged.single().usageCount)
    }

    @Test
    fun `pusta lista nie woła o nic`() {
        assertEquals(emptyList<ProductListItem>(), ProductUsageMerge.apply(emptyList(), emptyList()))
    }
}
