package pl.detailing.crm.leads.quotereply

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import java.time.Instant
import java.util.UUID

class QuoteStyleAnalyzerTest {

    private val chatClient = mockk<ChatClient>()
    private val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
    private val callSpec = mockk<ChatClient.CallResponseSpec>()

    private val analyzer = QuoteStyleAnalyzer(chatClient)
    private val studioId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.user(any<String>()) } returns requestSpec
        every { requestSpec.call() } returns callSpec
    }

    private fun stubLlm(value: String?) {
        every { callSpec.entity(QuoteStyleAnalyzer.StyleAnalysisResponse::class.java) } returns
            QuoteStyleAnalyzer.StyleAnalysisResponse(value)
    }

    private fun example(
        id: UUID = UUID.randomUUID(),
        updatedAt: Instant = Instant.parse("2026-01-01T00:00:00Z"),
        content: String = "Dzień dobry, cena to 1000 zł."
    ) = QuoteReplyExampleEntity(
        id = id,
        studioId = studioId,
        title = "Oferta",
        content = content,
        createdBy = UUID.randomUUID(),
        createdByName = "Jan",
        updatedBy = null,
        updatedByName = null,
        updatedAt = updatedAt
    )

    @Test
    fun `returns null and skips LLM when there are no examples`() = runBlocking {
        assertNull(analyzer.deriveStyleGuide(studioId, emptyList()))
        verify(exactly = 0) { chatClient.prompt() }
    }

    @Test
    fun `derives style guide from examples`() = runBlocking {
        stubLlm("- Mów o cenie, nie o inwestycji\n- Używaj kropek zamiast myślników")

        val guide = analyzer.deriveStyleGuide(studioId, listOf(example()))

        assertEquals("- Mów o cenie, nie o inwestycji\n- Używaj kropek zamiast myślników", guide)
    }

    @Test
    fun `caches result per studio and does not re-call LLM for the same examples`() = runBlocking {
        stubLlm("- Zwięźle")
        val examples = listOf(example())

        analyzer.deriveStyleGuide(studioId, examples)
        analyzer.deriveStyleGuide(studioId, examples)

        verify(exactly = 1) { chatClient.prompt() }
    }

    @Test
    fun `re-analyzes when an example is updated (signature changes)`() = runBlocking {
        val id = UUID.randomUUID()
        stubLlm("- v1")
        analyzer.deriveStyleGuide(studioId, listOf(example(id = id, updatedAt = Instant.parse("2026-01-01T00:00:00Z"))))

        stubLlm("- v2")
        analyzer.deriveStyleGuide(studioId, listOf(example(id = id, updatedAt = Instant.parse("2026-02-02T00:00:00Z"))))

        verify(exactly = 2) { chatClient.prompt() }
    }

    @Test
    fun `blank style guide is normalized to null`() = runBlocking {
        stubLlm("   ")

        assertNull(analyzer.deriveStyleGuide(studioId, listOf(example())))
    }

    @Test
    fun `LLM failure degrades gracefully to null`() = runBlocking {
        every { callSpec.entity(QuoteStyleAnalyzer.StyleAnalysisResponse::class.java) } throws
            RuntimeException("boom")

        assertNull(analyzer.deriveStyleGuide(studioId, listOf(example())))
    }
}
