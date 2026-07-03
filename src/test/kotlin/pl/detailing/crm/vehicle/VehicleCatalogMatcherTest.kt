package pl.detailing.crm.vehicle

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient

class VehicleCatalogMatcherTest {

    private val metadata = mockk<VehicleMetadataService>()
    private val chatClient = mockk<ChatClient>()
    private val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
    private val callSpec = mockk<ChatClient.CallResponseSpec>()

    private val matcher = VehicleCatalogMatcher(metadata, chatClient)

    private val brands = listOf("Audi", "Bmw", "Mercedes-benz", "Volkswagen")
    private val mercedesModels = listOf("Klasa C", "Klasa E", "Klasa G", "GLC", "Inny")

    @BeforeEach
    fun setUp() {
        every { metadata.getBrands() } returns brands
        every { metadata.getModelsForBrand("Mercedes-benz") } returns mercedesModels
        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.user(any<String>()) } returns requestSpec
        every { requestSpec.call() } returns callSpec
    }

    private fun stubLlm(value: String?) {
        every { callSpec.entity(VehicleCatalogMatcher.MatchResponse::class.java) } returns
            VehicleCatalogMatcher.MatchResponse(value)
    }

    @Test
    fun `matchBrand resolves exact match without calling the LLM`() = runBlocking {
        val result = matcher.matchBrand("bmw")

        assertEquals("Bmw", result)
        verify(exactly = 0) { chatClient.prompt() }
    }

    @Test
    fun `matchBrand falls back to LLM for colloquial make`() = runBlocking {
        stubLlm("Mercedes-benz")

        val result = matcher.matchBrand("mercedes")

        assertEquals("Mercedes-benz", result)
        verify(exactly = 1) { chatClient.prompt() }
    }

    @Test
    fun `matchBrand returns null when LLM answer is not a known brand`() = runBlocking {
        stubLlm("Ferrari")

        assertNull(matcher.matchBrand("ferrari"))
    }

    @Test
    fun `matchBrand returns null when LLM finds no match`() = runBlocking {
        stubLlm(null)

        assertNull(matcher.matchBrand("jakiś złom"))
    }

    @Test
    fun `matchModel resolves token-normalized match without calling the LLM`() = runBlocking {
        // "klasa-c" normalizes to the same token stream as "Klasa C"
        val result = matcher.matchModel("Mercedes-benz", "klasa-c")

        assertEquals("Klasa C", result)
        verify(exactly = 0) { chatClient.prompt() }
    }

    @Test
    fun `matchModel falls back to LLM for slang and maps g-wagon to Klasa G`() = runBlocking {
        stubLlm("Klasa G")

        val result = matcher.matchModel("Mercedes-benz", "g-wagon")

        assertEquals("Klasa G", result)
        verify(exactly = 1) { chatClient.prompt() }
    }

    @Test
    fun `matchModel returns null when brand has no catalog models`() = runBlocking {
        every { metadata.getModelsForBrand("Unknown") } returns emptyList()

        assertNull(matcher.matchModel("Unknown", "whatever"))
        verify(exactly = 0) { chatClient.prompt() }
    }

    @Test
    fun `resolve skips model matching when brand cannot be resolved`() = runBlocking {
        stubLlm(null) // brand LLM fallback finds nothing

        val match = matcher.resolve("nonsense-brand", "g-wagon")

        assertNull(match.brand)
        assertNull(match.model)
    }

    @Test
    fun `resolve derives model from a slang token misclassified as brand (g-wagon)`() = runBlocking {
        // Extraction gives brand="g-wagon", model=null. Brand resolves fuzzily to Mercedes-benz;
        // the same token is then matched as a model within that brand -> "Klasa G".
        stubLlm("Mercedes-benz") // first LLM call: brand fallback
        val match1 = matcher.matchBrand("g-wagon")
        assertEquals("Mercedes-benz", match1)

        // Re-stub for the model fallback call inside resolve()
        every { callSpec.entity(VehicleCatalogMatcher.MatchResponse::class.java) } returnsMany
            listOf(VehicleCatalogMatcher.MatchResponse("Mercedes-benz"), VehicleCatalogMatcher.MatchResponse("Klasa G"))

        val match = matcher.resolve("g-wagon", null)

        assertEquals("Mercedes-benz", match.brand)
        assertEquals("Klasa G", match.model)
    }

    @Test
    fun `resolve does not attempt brand-as-model fallback for an exact brand`() = runBlocking {
        // "bmw" matches a brand exactly; with no model mentioned we must not spend an LLM call.
        every { metadata.getModelsForBrand("Bmw") } returns listOf("Seria 3", "X5")

        val match = matcher.resolve("bmw", null)

        assertEquals("Bmw", match.brand)
        assertNull(match.model)
        verify(exactly = 0) { chatClient.prompt() }
    }

    @Test
    fun `resolve returns canonical brand and model for a colloquial mention`() = runBlocking {
        // brand resolves deterministically (exact), model needs the LLM
        stubLlm("Klasa G")

        val match = matcher.resolve("Mercedes-benz", "g-klasa")

        assertEquals("Mercedes-benz", match.brand)
        assertEquals("Klasa G", match.model)
    }
}
