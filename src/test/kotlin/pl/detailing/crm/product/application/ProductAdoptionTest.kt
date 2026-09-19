package pl.detailing.crm.product.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import pl.detailing.crm.product.infrastructure.ProductRepository
import pl.detailing.crm.product.infrastructure.ProductStudioEntity
import pl.detailing.crm.product.infrastructure.ProductStudioRepository
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.util.UUID

/**
 * Lista produktów pokazuje wyłącznie katalog tego studia, a wspólna tabela jest cache'em
 * rozpoznawania po kodzie. Skan kodu, który zapisało inne studio, musi więc zostawić ślad —
 * inaczej karta się otwiera, a produktu nigdy nie ma na liście.
 *
 * Drugi warunek jest ostrzejszy: adopcja NIE MOŻE ruszyć nakładki, która już istnieje.
 * Siedzi w niej cena wpisana przez człowieka i nazwa własna.
 */
class ProductAdoptionTest {

    private val studioId = StudioId(UUID.randomUUID())
    private val userId = UserId(UUID.randomUUID())
    private val productId = UUID.randomUUID()

    private val studioRepository = mockk<ProductStudioRepository>(relaxed = true)

    private val service = ProductCatalogService(
        productRepository = mockk<ProductRepository>(relaxed = true),
        studioRepository = studioRepository,
        ratingRepository = mockk(relaxed = true),
        noteRepository = mockk(relaxed = true),
        proposalRepository = mockk(relaxed = true),
        priceResolver = ProductPriceResolver(),
        visitProductRepository = mockk(relaxed = true),
        mapper = mockk(relaxed = true),
        objectMapper = com.fasterxml.jackson.databind.ObjectMapper(),
        resolutionService = mockk(relaxed = true)
    )

    @Test
    fun `produkt z cache trafia do katalogu studia`() {
        every { studioRepository.findByStudioIdAndProductId(studioId.value, productId) } returns null
        val saved = slot<ProductStudioEntity>()
        every { studioRepository.save(capture(saved)) } answers { saved.captured }

        service.adopt(studioId, userId, productId)

        assertEquals(studioId.value, saved.captured.studioId)
        assertEquals(productId, saved.captured.productId)
        assertNull(saved.captured.unitPriceNetCents, "adopcja nie wymyśla ceny")
        assertNull(saved.captured.internalName, "adopcja nie wymyśla nazwy własnej")
    }

    @Test
    fun `istniejaca nakladka zostaje nietknieta`() {
        val existing = ProductStudioEntity(
            id = UUID.randomUUID(),
            studioId = studioId.value,
            productId = productId,
            unitPriceNetCents = 154472,
            unitPriceGrossCents = 190000,
            priceEnteredAs = "GROSS",
            vatRate = 23,
            internalName = "Powłoka na czarne auta",
            internalNote = null,
            createdBy = userId.value,
            updatedBy = userId.value
        )
        every { studioRepository.findByStudioIdAndProductId(studioId.value, productId) } returns existing

        service.adopt(studioId, userId, productId)

        verify(exactly = 0) { studioRepository.save(any()) }
        assertEquals(190000, existing.unitPriceGrossCents, "cena wpisana przez człowieka zostaje")
        assertEquals("Powłoka na czarne auta", existing.internalName)
    }
}
