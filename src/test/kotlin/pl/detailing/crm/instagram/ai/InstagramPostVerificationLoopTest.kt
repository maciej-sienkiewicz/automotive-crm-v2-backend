package pl.detailing.crm.instagram.ai

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatOptions
import pl.detailing.crm.instagram.ai.generation.InstagramPostGeneratorService
import pl.detailing.crm.instagram.ai.model.FallbackInfo
import pl.detailing.crm.instagram.ai.model.InstagramInspirationContext
import pl.detailing.crm.instagram.ai.model.InstagramPostResult
import pl.detailing.crm.instagram.ai.model.RuleVerdict
import pl.detailing.crm.instagram.ai.model.VerificationReport
import pl.detailing.crm.instagram.ai.verification.InstagramPostVerifierService

/**
 * Pętla generuj → weryfikuj → popraw.
 *
 * Trzyma dwie rzeczy, na których cały mechanizm stoi: post nigdy nie wraca oznaczony jako
 * zweryfikowany, jeśli weryfikator go nie przepuścił, oraz pętla ma twardy koniec —
 * model uparcie łamiący regułę nie może w nieskończoność palić tokenów ani wywracać
 * żądania błędem 500.
 */
class InstagramPostVerificationLoopTest {

    private val chatClient = mockk<ChatClient>()
    private val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
    private val callSpec = mockk<ChatClient.CallResponseSpec>()

    private val service = InstagramPostGeneratorService(chatClient, InstagramPostVerifierService(chatClient))

    private val rules = listOf("Nie używaj emoji")

    @BeforeEach
    fun setUp() {
        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.system(any<String>()) } returns requestSpec
        every { requestSpec.user(any<String>()) } returns requestSpec
        every { requestSpec.options(any<OpenAiChatOptions>()) } returns requestSpec
        every { requestSpec.call() } returns callSpec
    }

    private fun context(styleNotes: List<String>) = InstagramInspirationContext(
        positiveExamples = emptyList(),
        negativeExamples = emptyList(),
        requestedTone = null,
        requestedLength = null,
        fallbackInfo = FallbackInfo.empty(),
        styleNotes = styleNotes
    )

    private fun passedReport() = VerificationReport(
        listOf(RuleVerdict(ruleIndex = 1, ruleText = rules[0], passed = true))
    )

    private fun failedReport() = VerificationReport(
        listOf(RuleVerdict(ruleIndex = 1, ruleText = rules[0], passed = false, violation = "Emoji w pierwszej linii"))
    )

    @Test
    fun `draft zgodny z regulami konczy petle po jednej rundzie`() = runBlocking {
        every { callSpec.entity(InstagramPostResult::class.java) } returns InstagramPostResult("Czysty post")
        every { callSpec.entity(VerificationReport::class.java) } returns passedReport()

        val result = service.generateVerified("PPF na BMW", null, context(rules))

        assertTrue(result.verificationPassed)
        assertEquals(1, result.iterations)
        assertEquals("Czysty post", result.content)
        assertTrue(result.failedRules.isEmpty())
        // Generator + weryfikator — ani jednego wywołania więcej.
        verify(exactly = 2) { chatClient.prompt() }
    }

    @Test
    fun `naruszenie jest poprawiane i ponownie weryfikowane`() = runBlocking {
        every { callSpec.entity(InstagramPostResult::class.java) } returnsMany listOf(
            InstagramPostResult("Post z emoji 🔥"),
            InstagramPostResult("Post bez emoji")
        )
        every { callSpec.entity(VerificationReport::class.java) } returnsMany listOf(
            failedReport(),
            passedReport()
        )

        val result = service.generateVerified("PPF na BMW", null, context(rules))

        assertTrue(result.verificationPassed)
        assertEquals(2, result.iterations)
        assertEquals("Post bez emoji", result.content, "Zwracana jest wersja PO korekcie")
        // Generator, weryfikacja, korekta, weryfikacja.
        verify(exactly = 4) { chatClient.prompt() }
    }

    @Test
    fun `permanentne naruszenie zatrzymuje petle po trzech rundach bez udawania sukcesu`() = runBlocking {
        every { callSpec.entity(InstagramPostResult::class.java) } returns InstagramPostResult("Post z emoji 🔥")
        every { callSpec.entity(VerificationReport::class.java) } returns failedReport()

        val result = service.generateVerified("PPF na BMW", null, context(rules))

        assertFalse(result.verificationPassed)
        assertEquals(InstagramPostGeneratorService.MAX_VERIFICATION_ROUNDS, result.iterations)
        assertEquals(listOf("Nie używaj emoji"), result.failedRules)
        // Generator + 3 weryfikacje + 2 korekty (po ostatniej weryfikacji nie ma już rundy).
        verify(exactly = 6) { chatClient.prompt() }
    }

    @Test
    fun `brak regul pomija weryfikacje calkowicie`() = runBlocking {
        every { callSpec.entity(InstagramPostResult::class.java) } returns InstagramPostResult("Post bez reguł")

        val result = service.generateVerified("PPF na BMW", null, context(emptyList()))

        assertTrue(result.verificationPassed)
        assertEquals(0, result.iterations)
        verify(exactly = 1) { chatClient.prompt() }
        verify(exactly = 0) { callSpec.entity(VerificationReport::class.java) }
    }
}
