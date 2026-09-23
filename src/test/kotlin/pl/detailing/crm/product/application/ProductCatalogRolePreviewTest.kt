package pl.detailing.crm.product.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pl.detailing.crm.product.CreateProductRequest
import pl.detailing.crm.product.UpdateProductRequest
import pl.detailing.crm.product.domain.ProductSource
import pl.detailing.crm.product.domain.UnitOfMeasure
import pl.detailing.crm.product.domain.VerificationLevel
import pl.detailing.crm.product.infrastructure.ProductEntity
import pl.detailing.crm.product.infrastructure.ProductRepository
import pl.detailing.crm.product.infrastructure.ProductStudioEntity
import pl.detailing.crm.product.infrastructure.ProductStudioRepository
import pl.detailing.crm.rolepreview.RolePreviewOutboundGuard
import pl.detailing.crm.rolepreview.SimulatedEffectChannel
import pl.detailing.crm.shared.ConflictException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.UserId
import java.math.BigDecimal
import java.util.Optional
import java.util.UUID

/**
 * Katalog produktów jest wspólny dla wszystkich studiów: wiersz z kodem kreskowym widzi przy
 * skanowaniu każde studio, a usunięcie piaskownicy podglądu roli kasuje wyłącznie jej wiersze
 * prywatne. Piaskownica nie może więc ani dopisać, ani zmienić wiersza wspólnego.
 */
class ProductCatalogRolePreviewTest {

    private val sandboxStudio = StudioId(UUID.randomUUID())
    private val userId = UserId(UUID.randomUUID())
    private val productRepository = mockk<ProductRepository>(relaxed = true)
    private val overlayRepository = mockk<ProductStudioRepository>(relaxed = true)
    private val guard = mockk<RolePreviewOutboundGuard>()

    private val service = ProductCatalogService(
        productRepository = productRepository,
        studioRepository = overlayRepository,
        ratingRepository = mockk(relaxed = true),
        noteRepository = mockk(relaxed = true),
        proposalRepository = mockk(relaxed = true),
        priceResolver = ProductPriceResolver(),
        visitProductRepository = mockk(relaxed = true),
        mapper = mockk(relaxed = true),
        objectMapper = com.fasterxml.jackson.databind.ObjectMapper(),
        resolutionService = mockk(relaxed = true),
        rolePreviewGuard = guard
    )

    init {
        every { guard.intercepts(sandboxStudio.value, SimulatedEffectChannel.SHARED_DATA, null, any()) } returns true
        every { overlayRepository.save(any<ProductStudioEntity>()) } answers { firstArg() }
    }

    @Test
    fun `produkt z kodem kreskowym nie trafia z piaskownicy do wspolnego katalogu`() {
        every { productRepository.findByGtin(any()) } returns null

        assertThrows<ConflictException> {
            service.create(sandboxStudio, userId, CreateProductRequest(gtin = "5901234123457", name = "Wosk twardy"), canSeeCosts = false)
        }

        verify(exactly = 0) { productRepository.save(any()) }
    }

    @Test
    fun `produkt bez kodu zostaje prywatnym wierszem piaskownicy`() {
        every { productRepository.findByNaturalKey(any(), any(), any(), any(), any()) } returns null
        val saved = slot<ProductEntity>()
        every { productRepository.save(capture(saved)) } answers { saved.captured }

        service.create(sandboxStudio, userId, CreateProductRequest(gtin = null, name = "Pasta polerska"), canSeeCosts = false)

        assertEquals(sandboxStudio.value, saved.captured.ownerStudioId)
    }

    @Test
    fun `wiersza wspolnego piaskownica nie zmienia`() {
        val shared = sharedProduct()
        every { productRepository.findById(shared.id) } returns Optional.of(shared)

        assertThrows<ConflictException> {
            service.updateProduct(sandboxStudio, userId, shared.id, UpdateProductRequest(name = "Inna nazwa"), canSeeCosts = false)
        }

        assertEquals("Wosk twardy", shared.name)
        verify(exactly = 0) { productRepository.save(any()) }
    }

    private fun sharedProduct() = ProductEntity(
        id = UUID.randomUUID(),
        gtin = "5901234123457",
        name = "Wosk twardy",
        brand = "Marka",
        unitOfMeasure = UnitOfMeasure.PIECE,
        packageSizeValue = BigDecimal.ONE,
        packageSizeUnit = UnitOfMeasure.PIECE,
        packageHeightMm = null,
        packageWidthMm = null,
        packageDepthMm = null,
        description = null,
        imageFileId = null,
        source = ProductSource.MANUAL,
        verificationLevel = VerificationLevel.UNVERIFIED,
        sourceConfidence = null,
        sourcePayload = null,
        resolvedAt = null,
        ownerStudioId = null,
        createdByStudioId = UUID.randomUUID(),
        createdBy = UUID.randomUUID(),
        updatedBy = UUID.randomUUID()
    )
}
