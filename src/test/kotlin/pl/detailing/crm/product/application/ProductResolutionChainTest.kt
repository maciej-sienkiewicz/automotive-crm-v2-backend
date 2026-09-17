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
import pl.detailing.crm.product.adapter.web.WebProductProvider
import pl.detailing.crm.product.domain.Gtin
import pl.detailing.crm.product.domain.PackageDimensions
import pl.detailing.crm.product.domain.ProductSource
import pl.detailing.crm.product.domain.UnitOfMeasure
import pl.detailing.crm.product.domain.ProductSpec
import pl.detailing.crm.product.domain.VerificationLevel
import pl.detailing.crm.product.port.ProductLookupResult
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertNotNull
import java.math.BigDecimal

/**
 * Łańcuch rozpoznawania: LOCAL → AI (próg 0,90) → GS1.
 *
 * Niezmienniki:
 *  - AI ≥ progu to trafienie pewne (RESOLVED),
 *  - AI < progu najpierw PRÓBUJE kolejnych dostawców; gdy któryś (np. GS1) ma dane, to one
 *    wygrywają nad szkicem,
 *  - gdy żaden dostawca nie ma pewnej karty, oddajemy najlepszy odczyt AI jako SZKIC
 *    (RESOLVED, poziom AI_SUGGESTED, niska pewność) do ręcznego potwierdzenia — łagodne
 *    zejście zamiast NOT_FOUND. To NIE jest wpis do katalogu: nic się nie zapisuje samo,
 *    nic nie awansuje na „zweryfikowane". Kod bez żadnego odczytu (puste pola / null) dalej
 *    daje NOT_FOUND i niczego nie zmyśla.
 */
class ProductResolutionChainTest {

    private val local = mockk<LocalCatalogProvider>()
    private val ai = mockk<AiProductProvider>()
    private val gs1 = mockk<Gs1ProductProvider>()
    private val web = mockk<WebProductProvider>()
    private val redis = mockk<StringRedisTemplate>(relaxed = true)
    private val valueOps = mockk<ValueOperations<String, String>>(relaxed = true)

    private val GTIN = "5901234123457"

    private fun spec(source: String) = ProductSpec(
        gtin = "05901234123457",
        name = "Powłoka $source",
        brand = "TestBrand",
        unitOfMeasure = UnitOfMeasure.ML,
        packageSizeValue = BigDecimal("50"),
        packageSizeUnit = UnitOfMeasure.ML,
        dimensions = PackageDimensions.EMPTY,
        description = null,
        imageFileId = null
    )

    private fun service(draftFloor: Double = 0.0) = ProductResolutionService(
        local = local, ai = ai, web = web, gs1 = gs1, redisTemplate = redis,
        orderRaw = "LOCAL,AI,GS1", aiMinConfidence = 0.90,
        aiDraftMinConfidence = draftFloor, negativeCacheTtlDays = 7
    )

    private fun baseStubs() {
        every { redis.hasKey(any()) } returns false
        every { redis.opsForValue() } returns valueOps
        every { valueOps.set(any(), any(), any<java.time.Duration>()) } just Runs
        every { ai.enabled } returns true
        every { gs1.enabled } returns true
        // Ten test pilnuje bramek AI/GS1; krok WEB ma własny test.
        every { web.enabled } returns false
        every { web.sourcesSignature } returns "of=false|s=NONE"
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
    fun `AI below threshold with no GS1 is surfaced as a draft, not fabricated as verified`() = runBlocking {
        baseStubs()
        coEvery { ai.findByGtin(any()) } returns ProductLookupResult(spec("AI"), ProductSource.AI, 0.50, null)
        every { gs1.enabled } returns false

        val r = service().resolve(GTIN)
        // Łagodne zejście: szkic do potwierdzenia — RESOLVED, ale jawnie niepewny.
        assertEquals(ProductResolution.Status.RESOLVED, r.status)
        assertNotNull(r.result)
        assertEquals(ProductSource.AI, r.result!!.source)
        assertEquals(0.50, r.result!!.confidence)
        // Kluczowe: to NIE jest trafienie lokalne i NIE awansuje na zweryfikowane.
        assertEquals(VerificationLevel.AI_SUGGESTED, r.verificationLevel())
    }

    @Test
    fun `a returned draft is not negatively cached`() = runBlocking {
        baseStubs()
        coEvery { ai.findByGtin(any()) } returns ProductLookupResult(spec("AI"), ProductSource.AI, 0.50, null)
        every { gs1.enabled } returns false

        service().resolve(GTIN)
        // Kod, który dał szkic, ma znów pokazać kartę przy kolejnym skanie — nie wpisujemy
        // go do negatywnego cache jako „miss".
        verify(exactly = 0) { valueOps.set(any(), any(), any<java.time.Duration>()) }
    }

    @Test
    fun `WEB is tried before AI and a confident web hit wins`() = runBlocking {
        baseStubs()
        every { web.enabled } returns true
        coEvery { web.findByGtin(any()) } returns ProductLookupResult(spec("WEB"), ProductSource.WEB, 0.95, null)
        coEvery { ai.findByGtin(any()) } returns ProductLookupResult(spec("AI"), ProductSource.AI, 0.99, null)

        val r = ProductResolutionService(
            local = local, ai = ai, web = web, gs1 = gs1, redisTemplate = redis,
            orderRaw = "LOCAL,WEB,AI,GS1", aiMinConfidence = 0.90,
            aiDraftMinConfidence = 0.0, negativeCacheTtlDays = 7
        ).resolve(GTIN)

        // Dane z sieci biją pamięć modelu: model nie ma dostępu do internetu i dla realnego
        // kodu i tak oddałby pustkę — kolejność LOCAL,WEB,AI jest tu istotą naprawy.
        assertEquals(ProductResolution.Status.RESOLVED, r.status)
        assertEquals(ProductSource.WEB, r.result!!.source)
        assertEquals(VerificationLevel.AI_SUGGESTED, r.verificationLevel())
    }

    @Test
    fun `WEB below threshold falls through to AI but is kept as a draft`() = runBlocking {
        baseStubs()
        every { web.enabled } returns true
        every { gs1.enabled } returns false
        coEvery { web.findByGtin(any()) } returns ProductLookupResult(spec("WEB"), ProductSource.WEB, 0.60, null)
        coEvery { ai.findByGtin(any()) } returns null

        val r = ProductResolutionService(
            local = local, ai = ai, web = web, gs1 = gs1, redisTemplate = redis,
            orderRaw = "LOCAL,WEB,AI,GS1", aiMinConfidence = 0.90,
            aiDraftMinConfidence = 0.0, negativeCacheTtlDays = 7
        ).resolve(GTIN)

        assertEquals(ProductResolution.Status.RESOLVED, r.status)
        assertEquals(ProductSource.WEB, r.result!!.source)
        assertEquals(0.60, r.result!!.confidence)
    }

    @Test
    fun `AI below the draft floor is dropped and yields NOT_FOUND`() = runBlocking {
        baseStubs()
        coEvery { ai.findByGtin(any()) } returns ProductLookupResult(spec("AI"), ProductSource.AI, 0.30, null)
        every { gs1.enabled } returns false

        // Z podniesionym progiem szkicu najmniej pewne odczyty są odsiewane.
        val r = service(draftFloor = 0.40).resolve(GTIN)
        assertEquals(ProductResolution.Status.NOT_FOUND, r.status)
    }
}
