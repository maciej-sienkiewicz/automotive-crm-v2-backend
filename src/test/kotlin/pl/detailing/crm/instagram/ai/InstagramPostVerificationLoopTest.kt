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
import pl.detailing.crm.instagram.ai.verification.StyleRuleChecker

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

    private val service = InstagramPostGeneratorService(chatClient, InstagramPostVerifierService(chatClient, StyleRuleChecker()))

    // Reguła jakościowa: policzalne („bez emoji", „3 bullet pointy") rozstrzyga
    // StyleRuleChecker bez pytania modelu, więc nie nadają się do testu samej pętli.
    private val rules = listOf("Pisz ciepłym, bezpośrednim tonem")

    private val twoRules = listOf("Pisz ciepłym, bezpośrednim tonem", "Nie obiecuj efektów bez pokrycia")

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
        listOf(RuleVerdict(ruleIndex = 1, ruleText = rules[0], passed = false, violation = "Ton urzędowy w pierwszym akapicie"))
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
            InstagramPostResult("Post w tonie urzędowym"),
            InstagramPostResult("Post w ciepłym tonie")
        )
        every { callSpec.entity(VerificationReport::class.java) } returnsMany listOf(
            failedReport(),
            passedReport()
        )

        val result = service.generateVerified("PPF na BMW", null, context(rules))

        assertTrue(result.verificationPassed)
        assertEquals(2, result.iterations)
        assertEquals("Post w ciepłym tonie", result.content, "Zwracana jest wersja PO korekcie")
        // Generator, weryfikacja, korekta, weryfikacja.
        verify(exactly = 4) { chatClient.prompt() }
    }

    @Test
    fun `permanentne naruszenie zatrzymuje petle po trzech rundach bez udawania sukcesu`() = runBlocking {
        every { callSpec.entity(InstagramPostResult::class.java) } returnsMany listOf(
            InstagramPostResult("Post w tonie urzędowym"),
            InstagramPostResult("Nadal w tonie urzędowym"),
            InstagramPostResult("Wciąż w tonie urzędowym")
        )
        every { callSpec.entity(VerificationReport::class.java) } returns failedReport()

        val result = service.generateVerified("PPF na BMW", null, context(rules))

        assertFalse(result.verificationPassed)
        assertEquals(InstagramPostGeneratorService.MAX_VERIFICATION_ROUNDS, result.iterations)
        assertEquals(listOf("Pisz ciepłym, bezpośrednim tonem"), result.failedRules)
        // Generator + 3 weryfikacje + 2 korekty (po ostatniej weryfikacji nie ma już rundy).
        verify(exactly = 6) { chatClient.prompt() }
    }

    @Test
    fun `korekta oddajaca ten sam tekst konczy petle wczesniej`() = runBlocking {
        // Korektor zwraca draft bez zmian — kolejna runda to dwa wywołania modelu
        // z góry wiadomym werdyktem.
        every { callSpec.entity(InstagramPostResult::class.java) } returns InstagramPostResult("Post w tonie urzędowym")
        every { callSpec.entity(VerificationReport::class.java) } returns failedReport()

        val result = service.generateVerified("PPF na BMW", null, context(rules))

        assertFalse(result.verificationPassed, "Zatrzymanie pętli to nie to samo co zgodność ze stylem")
        assertEquals(1, result.iterations)
        assertEquals("Post w tonie urzędowym", result.content)
        // Generator + weryfikacja + jedna korekta. Bez wczesnego wyjścia byłoby 6.
        verify(exactly = 3) { chatClient.prompt() }
    }

    @Test
    fun `zwracany jest najlepszy draft, a nie ostatni`() = runBlocking {
        val second = "Pisz ciepło, ale obiecaj cuda"
        every { callSpec.entity(InstagramPostResult::class.java) } returnsMany listOf(
            InstagramPostResult("Draft z jednym potknięciem"),
            InstagramPostResult(second),
            InstagramPostResult("Trzecia wersja, wciąż gorsza")
        )
        // Korekta cofnęła jakość: z jednego naruszenia zrobiły się dwa i takie zostały.
        every { callSpec.entity(VerificationReport::class.java) } returnsMany listOf(
            VerificationReport(listOf(
                RuleVerdict(1, twoRules[0], passed = false, violation = "ton urzędowy"),
                RuleVerdict(2, twoRules[1], passed = true)
            )),
            VerificationReport(listOf(
                RuleVerdict(1, twoRules[0], passed = false, violation = "ton urzędowy"),
                RuleVerdict(2, twoRules[1], passed = false, violation = "obietnica 100% efektu")
            )),
            VerificationReport(listOf(
                RuleVerdict(1, twoRules[0], passed = false, violation = "ton urzędowy"),
                RuleVerdict(2, twoRules[1], passed = false, violation = "obietnica 100% efektu")
            ))
        )

        val result = service.generateVerified("PPF na BMW", null, context(twoRules))

        assertEquals("Draft z jednym potknięciem", result.content, "Oddajemy wersję z najmniejszą liczbą naruszeń")
        assertEquals(listOf(twoRules[0]), result.failedRules, "Raport opisuje wersję, którą naprawdę oddajemy")
        assertFalse(result.verificationPassed)
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
