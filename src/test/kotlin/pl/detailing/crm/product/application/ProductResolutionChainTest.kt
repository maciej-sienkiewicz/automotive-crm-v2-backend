package pl.detailing.crm.product.application

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import pl.detailing.crm.product.adapter.local.LocalCatalogProvider
import pl.detailing.crm.product.adapter.web.WebProductProvider
import pl.detailing.crm.product.domain.PackageDimensions
import pl.detailing.crm.product.domain.ProductSource
import pl.detailing.crm.product.domain.ProductSpec
import pl.detailing.crm.product.domain.UnitOfMeasure
import pl.detailing.crm.product.domain.VerificationLevel
import pl.detailing.crm.product.port.ProductLookupResult
import java.math.BigDecimal

/**
 * Rozpoznanie ma DWA kroki: nasz katalog → wyszukiwanie w sieci.
 *
 * Niezmienniki:
 *  - trafienie w katalogu kończy sprawę i nie płaci za wyszukiwanie,
 *  - wynik z sieci ≥ progu to trafienie; poniżej progu wraca jako SZKIC do potwierdzenia
 *    (poziom AI_SUGGESTED, nigdy GS1_VERIFIED) — łagodne zejście zamiast NOT_FOUND,
 *  - kod, którego sieć nie zna, daje NOT_FOUND i NICZEGO nie zmyśla,
 *  - kod, który dał szkic, NIE trafia do negatywnego cache (kolejny skan ma znów pokazać
 *    kartę), a kod bez wyniku — trafia.
 */
class ProductResolutionChainTest {

    private val local = mockk<LocalCatalogProvider>()
    private val web = mockk<WebProductProvider>()
    private val redis = mockk<StringRedisTemplate>(relaxed = true)
    private val valueOps = mockk<ValueOperations<String, String>>(relaxed = true)

    private val GTIN = "5901234123457"

    private fun spec(name: String) = ProductSpec(
        gtin = "05901234123457",
        name = "Powłoka $name",
        brand = "TestBrand",
        unitOfMeasure = UnitOfMeasure.ML,
        packageSizeValue = BigDecimal("50"),
        packageSizeUnit = UnitOfMeasure.ML,
        dimensions = PackageDimensions.EMPTY,
        description = null,
        imageFileId = null
    )

    private fun service(draftFloor: Double = 0.0) = ProductResolutionService(
        local = local, web = web, redisTemplate = redis,
        minConfidence = 0.90, draftMinConfidence = draftFloor, negativeCacheTtlDays = 7
    )

    private fun baseStubs() {
        every { redis.hasKey(any()) } returns false
        every { redis.opsForValue() } returns valueOps
        every { valueOps.set(any(), any(), any<java.time.Duration>()) } just Runs
        every { web.enabled } returns true
        every { web.sourcesSignature } returns "gpt-4o-mini-search-preview"
        coEvery { local.findByGtin(any()) } returns null
    }

    @Test
    fun `trafienie w naszym katalogu konczy sprawe i nie pyta sieci`() = runBlocking {
        baseStubs()
        coEvery { local.findByGtin(any()) } returns ProductLookupResult(spec("LOCAL"), ProductSource.MANUAL, 1.0, null)

        val r = service().resolve(GTIN)

        assertEquals(ProductResolution.Status.FOUND_LOCAL, r.status)
        // Katalog jest darmowy — nie wolno płacić za wyszukiwanie tego, co już mamy.
        coEvery { web.findByGtin(any()) } returns null
        verify(exactly = 0) { valueOps.set(any(), any(), any<java.time.Duration>()) }
    }

    @Test
    fun `wynik z sieci powyzej progu jest trafieniem`() = runBlocking {
        baseStubs()
        coEvery { web.findByGtin(any()) } returns ProductLookupResult(spec("WEB"), ProductSource.WEB, 0.95, null)

        val r = service().resolve(GTIN)

        assertEquals(ProductResolution.Status.RESOLVED, r.status)
        assertEquals(ProductSource.WEB, r.result!!.source)
        // Dane ze sklepów to nie rejestr — człowiek potwierdza zgodność z etykietą.
        assertEquals(VerificationLevel.AI_SUGGESTED, r.verificationLevel())
    }

    @Test
    fun `wynik ponizej progu wraca jako szkic, nie jako NOT_FOUND`() = runBlocking {
        baseStubs()
        coEvery { web.findByGtin(any()) } returns ProductLookupResult(spec("WEB"), ProductSource.WEB, 0.50, null)

        val r = service().resolve(GTIN)

        assertEquals(ProductResolution.Status.RESOLVED, r.status)
        assertNotNull(r.result)
        assertEquals(0.50, r.result!!.confidence)
        assertEquals(VerificationLevel.AI_SUGGESTED, r.verificationLevel())
    }

    @Test
    fun `szkic nie trafia do negatywnego cache`() = runBlocking {
        baseStubs()
        coEvery { web.findByGtin(any()) } returns ProductLookupResult(spec("WEB"), ProductSource.WEB, 0.50, null)

        service().resolve(GTIN)

        verify(exactly = 0) { valueOps.set(any(), any(), any<java.time.Duration>()) }
    }

    @Test
    fun `kod nieznany sieci daje NOT_FOUND i nic nie zmysla`() = runBlocking {
        baseStubs()
        coEvery { web.findByGtin(any()) } returns null

        val r = service().resolve(GTIN)

        assertEquals(ProductResolution.Status.NOT_FOUND, r.status)
        assertEquals(null, r.result)
        verify { valueOps.set(any(), any(), any<java.time.Duration>()) }
    }

    @Test
    fun `wynik ponizej progu szkicu jest odsiewany`() = runBlocking {
        baseStubs()
        coEvery { web.findByGtin(any()) } returns ProductLookupResult(spec("WEB"), ProductSource.WEB, 0.30, null)

        val r = service(draftFloor = 0.40).resolve(GTIN)

        assertEquals(ProductResolution.Status.NOT_FOUND, r.status)
    }

    @Test
    fun `negatywny cache pomija wyszukiwanie`() = runBlocking {
        baseStubs()
        every { redis.hasKey(any()) } returns true

        val r = service().resolve(GTIN)

        assertEquals(ProductResolution.Status.NOT_FOUND, r.status)
        // Klucz niesie sygnaturę modelu, więc zmiana modelu unieważnia stare „miss".
        verify { redis.hasKey(match<String> { it.contains("gpt-4o-mini-search-preview") }) }
    }

    @Test
    fun `wylaczone wyszukiwanie nie wywraca rozpoznania`() = runBlocking {
        baseStubs()
        every { web.enabled } returns false
        every { web.sourcesSignature } returns "off"

        val r = service().resolve(GTIN)

        assertEquals(ProductResolution.Status.NOT_FOUND, r.status)
    }
}
