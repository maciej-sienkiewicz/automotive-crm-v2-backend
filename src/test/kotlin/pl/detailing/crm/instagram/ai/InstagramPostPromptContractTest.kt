package pl.detailing.crm.instagram.ai

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatOptions
import pl.detailing.crm.instagram.ai.config.InstagramAiModels
import pl.detailing.crm.instagram.ai.generation.InstagramPostGeneratorService
import pl.detailing.crm.instagram.ai.model.FallbackInfo
import pl.detailing.crm.instagram.ai.model.InstagramInspirationContext
import pl.detailing.crm.instagram.ai.model.InstagramPostResult
import pl.detailing.crm.instagram.ai.model.RuleVerdict
import pl.detailing.crm.instagram.ai.model.VerificationReport
import pl.detailing.crm.instagram.ai.verification.InstagramPostVerifierService
import pl.detailing.crm.instagram.ai.verification.StyleRuleChecker

/**
 * Umowa promptów: co prompt generatora i korektora MUSI zawierać, żeby pętla nie
 * walczyła sama ze sobą.
 *
 * Regresja z produkcji: prompt kazał wypunktowania oznaczać ikonami ✅ i dokładać
 * 5-8 hashtagów, a reguły studia („bez emoji", limit hashtagów) stały w jego środku
 * z adnotacją „najwyższy priorytet". Model czytał konkret postawiony NIŻEJ jako
 * ważniejszy, generował draft łamiący regułę studia, weryfikator to wyłapywał i pętla
 * poprawiała to, co sam prompt przed chwilą zamówił — trzy rundy na każde żądanie.
 */
class InstagramPostPromptContractTest {

    private val chatClient = mockk<ChatClient>()
    private val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
    private val callSpec = mockk<ChatClient.CallResponseSpec>()

    private val systemMessages = mutableListOf<String>()
    private val userMessages = mutableListOf<String>()
    private val requestOptions = mutableListOf<OpenAiChatOptions>()

    private val rules = listOf("Pisz ciepłym, bezpośrednim tonem", "Nie obiecuj efektów bez pokrycia")

    private fun service(models: InstagramAiModels = InstagramAiModels()) = InstagramPostGeneratorService(
        chatClient,
        InstagramPostVerifierService(chatClient, StyleRuleChecker(), models),
        models
    )

    @BeforeEach
    fun setUp() {
        systemMessages.clear()
        userMessages.clear()
        requestOptions.clear()
        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.system(capture(systemMessages)) } returns requestSpec
        every { requestSpec.user(capture(userMessages)) } returns requestSpec
        every { requestSpec.options(capture(requestOptions)) } returns requestSpec
        every { requestSpec.call() } returns callSpec
        every { callSpec.entity(InstagramPostResult::class.java) } returns InstagramPostResult("Post")
    }

    private fun context(styleNotes: List<String>) = InstagramInspirationContext(
        positiveExamples = emptyList(),
        negativeExamples = emptyList(),
        requestedTone = null,
        requestedLength = null,
        fallbackInfo = FallbackInfo.empty(),
        styleNotes = styleNotes
    )

    private suspend fun generatorPrompt(styleNotes: List<String> = rules): String =
        service().generateWithDebug("PPF na BMW", null, context(styleNotes)).systemMessage

    @Test
    fun `reguly studia zamykaja prompt`() = runBlocking {
        val prompt = generatorPrompt()

        assertTrue(
            prompt.indexOf("REGUŁY STUDIA") > prompt.indexOf("ZASADY DOMYŚLNE"),
            "Instrukcja przeczytana jako ostatnia rozstrzyga remisy — reguły studia idą po zasadach domyślnych"
        )
        assertTrue(
            prompt.trimEnd().endsWith("sprawdź gotowy post po kolei względem każdej reguły studia."),
            "Po regułach studia nie może już stać żadna inna instrukcja:\n${prompt.takeLast(200)}"
        )
    }

    @Test
    fun `prompt nie zamawia emoji, ktore regula studia moze zakazac`() = runBlocking {
        val prompt = generatorPrompt()

        listOf("✅", "✔️", "🛡️").forEach { icon ->
            assertTrue(icon !in prompt, "Prompt nie może na sztywno kazać wstawiać $icon — studio może mieć regułę «bez emoji»")
        }
    }

    @Test
    fun `prompt mowi wprost, ktora instrukcja wygrywa przy sprzecznosci`() = runBlocking {
        val prompt = generatorPrompt()

        assertTrue(
            "reguła studia > powód odrzucenia > ton i długość > zasada domyślna > przykład" in prompt,
            "Bez drabiny pierwszeństwa model sam wybiera, czego posłuchać"
        )
        assertTrue(
            "reguła o liczbie hashtagów zastępuje punkt 5" in prompt,
            "Hashtagi to najczęstszy konflikt: prompt zamawia 5-8, reguła studia może dopuszczać mniej"
        )
    }

    @Test
    fun `bez regul studia prompt to mowi zamiast milczec`() = runBlocking {
        val prompt = generatorPrompt(emptyList())

        assertTrue("Studio nie ustawiło własnych reguł" in prompt)
        assertTrue("REGUŁY STUDIA" in prompt, "Zasady domyślne odwołują się do tej sekcji — musi istnieć")
    }

    @Test
    fun `korektor widzi komplet regul, nie tylko te zlamane`() = runBlocking {
        every { callSpec.entity(VerificationReport::class.java) } returns VerificationReport(listOf(
            RuleVerdict(1, rules[0], passed = false, violation = "ton urzędowy"),
            RuleVerdict(2, rules[1], passed = true)
        ))

        service().generateVerified("PPF na BMW", null, context(rules))

        // Generator, weryfikator, korektor — prompt korekty jest ostatni.
        val correctorPrompt = userMessages.last()
        assertTrue("=== WSZYSTKIE OBOWIĄZUJĄCE REGUŁY ===" in correctorPrompt)
        assertTrue(
            rules[1] in correctorPrompt,
            "Reguła spełniona też musi być w prompcie — inaczej korekta jednej reguły łamie drugą i pętla oscyluje"
        )
        assertTrue("=== NARUSZENIA DO USUNIĘCIA ===" in correctorPrompt)
    }

    @Test
    fun `domyslnie generator nie nadpisuje modelu z konfiguracji globalnej`() = runBlocking {
        service().generate("PPF na BMW", null, context(emptyList()))

        verify(exactly = 0) { requestSpec.options(any<OpenAiChatOptions>()) }
    }

    @Test
    fun `skonfigurowany model kroku trafia do zadania`() = runBlocking {
        val models = InstagramAiModels(generatorModel = "gpt-4.1", correctorModel = "gpt-4.1")

        service(models).generate("PPF na BMW", null, context(emptyList()))

        assertEquals("gpt-4.1", requestOptions.single().model)
        assertEquals(
            InstagramAiModels.CORRECTOR_TEMPERATURE,
            models.corrector.temperature,
            "Model kroku zmieniamy niezależnie od jego temperatury"
        )
    }
}
