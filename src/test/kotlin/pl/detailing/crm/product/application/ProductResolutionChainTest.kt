package pl.detailing.crm.product.application

import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import pl.detailing.crm.product.adapter.ai.AiProductProvider
import pl.detailing.crm.product.adapter.gs1.Gs1ProductProvider
import pl.detailing.crm.product.adapter.local.LocalCatalogProvider
import pl.detailing.crm.product.domain.Gtin
import pl.detailing.crm.product.domain.PackageDimensions
import pl.detailing.crm.product.domain.ProductSource
import pl.detailing.crm.product.domain.UnitOfMeasure
import pl.detailing.crm.product.domain.ProductSpec
import pl.detailing.crm.product.port.ProductLookupResult
import java.math.BigDecimal

/**
 * Łańcuch rozpoznawania: LOCAL → AI (z progiem 0,90) → GS1. Kluczowy niezmiennik
 * punktu 2 wymagania: pewność AI < progu SCHODZI do GS1; nierozpoznany kod NIE zapisuje
 * niczego zmyślonego.
 */
class ProductResolutionChainTest {

    private val local = mockk<LocalCatalogProvider>()
    private val ai = mockk<AiProductProvider>()
    private val gs1 = mockk<Gs1ProductProvider>()
    private val redis = mockk<StringRedisTemplate>(relaxed = true)
    private val valueOps = mockk<ValueOperations<String, String>>(relaxed = true)

    private val GTIN = "5901234123457"

    private fun spec(source: String) = ProductSpec(
        gtin = "05901234123457",
        name = "Powłoka $source",
        brand = "TestBrand",
        manufacturerName = "TestBrand",
        unitOfMeasure = UnitOfMeasure.ML,
        packageSizeValue = BigDecimal("50"),
        packageSizeUnit = UnitOfMeasure.ML,
        dimensions = PackageDimensions.EMPTY,
        description = null,
        imageFileId = null
    )

    private fun service() = ProductResolutionService(
        local = local, ai = ai, gs1 = gs1, redisTemplate = redis,
        orderRaw = "LOCAL,AI,GS1", aiMinConfidence = 0.90, negativeCacheTtlDays = 7
    )

    private fun baseStubs() {
        every { redis.hasKey(any()) } returns false
        every { redis.opsForValue() } returns valueOps
        every { valueOps.set(any(), any(), any<java.time.Duration>()) } just Runs
        every { ai.enabled } returns true
        every { gs1.enabled } returns true
        coEvery { local.findByGtin(any()) } returns null
    }

    @Test
    fun `local hit short-circuits the chain`() = runBlocking {
        baseStubs()
        coEvery { local.findByGtin(any()) } returns ProductLookupResult(spec("LOCAL"), ProductSource.GS1, 1.0, null)

        val r = service().resolve(GTIN)
        assertEquals(ProductResolution.Status.FOUND_LOCAL, r.status)
    }

    @Test
    fun `AI at or above threshold is accepted`() = runBlocking {
        baseStubs()
        coEvery { ai.findByGtin(any()) } returns ProductLookupResult(spec("AI"), ProductSource.AI, 0.90, null)

        val r = service().resolve(GTIN)
        assertEquals(ProductResolution.Status.RESOLVED, r.status)
        assertEquals(ProductSource.AI, r.result!!.source)
    }

    @Test
    fun `AI below threshold falls through to GS1`() = runBlocking {
        baseStubs()
        coEvery { ai.findByGtin(any()) } returns ProductLookupResult(spec("AI"), ProductSource.AI, 0.89, null)
        coEvery { gs1.findByGtin(any()) } returns ProductLookupResult(spec("GS1"), ProductSource.GS1, 1.0, null)

        val r = service().resolve(GTIN)
        assertEquals(ProductResolution.Status.RESOLVED, r.status)
        assertEquals(ProductSource.GS1, r.result!!.source)
    }

    @Test
    fun `nothing found returns NOT_FOUND and invents nothing`() = runBlocking {
        baseStubs()
        coEvery { ai.findByGtin(any()) } returns null
        coEvery { gs1.findByGtin(any()) } returns null

        val r = service().resolve(GTIN)
        assertEquals(ProductResolution.Status.NOT_FOUND, r.status)
        assertEquals(null, r.result)
    }

    @Test
    fun `AI below threshold and no GS1 does not fabricate a product`() = runBlocking {
        baseStubs()
        coEvery { ai.findByGtin(any()) } returns ProductLookupResult(spec("AI"), ProductSource.AI, 0.50, null)
        every { gs1.enabled } returns false

        val r = service().resolve(GTIN)
        assertEquals(ProductResolution.Status.NOT_FOUND, r.status)
    }
}
