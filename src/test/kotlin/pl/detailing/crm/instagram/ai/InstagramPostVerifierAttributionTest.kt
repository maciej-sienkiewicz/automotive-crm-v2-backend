package pl.detailing.crm.instagram.ai

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatOptions
import pl.detailing.crm.instagram.ai.model.RuleVerdict
import pl.detailing.crm.instagram.ai.model.VerificationReport
import pl.detailing.crm.instagram.ai.verification.InstagramPostVerifierService

/**
 * Przypisanie werdyktów modelu do reguł.
 *
 * Regresja z produkcji: post bez ani jednego emoji wracał z werdyktem „łamie regułę
 * «bez emoji»", trzy rundy korekty i oznaczeniem verificationPassed=false. Model
 * ponumerował werdykty od zera, a kod czytał je jako numerowane od jedynki — naruszenie
 * reguły o odstępach między punktami wylądowało na regule o emoji. Fałszywe naruszenie
 * jest gorsze niż przeoczone: uruchamia korektę poprawnego tekstu i psuje gotowy post.
 */
class InstagramPostVerifierAttributionTest {

    private val chatClient = mockk<ChatClient>()
    private val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
    private val callSpec = mockk<ChatClient.CallResponseSpec>()

    private val service = InstagramPostVerifierService(chatClient)

    private val rules = listOf("bez emoji", "bez przerw między punktami listy", "maksymalnie 8 hashtagów")

    @BeforeEach
    fun setUp() {
        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.system(any<String>()) } returns requestSpec
        every { requestSpec.user(any<String>()) } returns requestSpec
        every { requestSpec.options(any<OpenAiChatOptions>()) } returns requestSpec
        every { requestSpec.call() } returns callSpec
    }

    private fun stub(report: VerificationReport) {
        every { callSpec.entity(VerificationReport::class.java) } returns report
    }

    @Test
    fun `numeracja od zera nie przesuwa naruszenia na sasiednia regule`() = runBlocking {
        // Model liczy od zera: naruszenie dotyczy reguły o odstępach (jego indeks 1).
        stub(VerificationReport(listOf(
            RuleVerdict(0, "bez emoji", passed = true),
            RuleVerdict(1, "bez przerw między punktami listy", passed = false, violation = "pusta linia między punktami"),
            RuleVerdict(2, "maksymalnie 8 hashtagów", passed = true)
        )))

        val verdicts = service.verify("Post bez emoji", rules).verdicts

        assertTrue(verdicts[0].passed, "Reguła o emoji nie może przejąć cudzego naruszenia")
        assertFalse(verdicts[1].passed)
        assertEquals("pusta linia między punktami", verdicts[1].violation)
        assertTrue(verdicts[2].passed)
    }

    @Test
    fun `werdykty w innej kolejnosci trafiaja do wlasciwych regul po tresci`() = runBlocking {
        stub(VerificationReport(listOf(
            RuleVerdict(1, "maksymalnie 8 hashtagów", passed = false, violation = "11 hashtagów na końcu"),
            RuleVerdict(2, "bez emoji", passed = true),
            RuleVerdict(3, "bez przerw między punktami listy", passed = true)
        )))

        val verdicts = service.verify("Post", rules).verdicts

        assertTrue(verdicts[0].passed, "«bez emoji» dostało numer 2, ale treść mówi, czyj to werdykt")
        assertTrue(verdicts[1].passed)
        assertFalse(verdicts[2].passed)
        assertEquals("11 hashtagów na końcu", verdicts[2].violation)
    }

    @Test
    fun `naruszenie bez wskazania fragmentu nie jest naruszeniem`() = runBlocking {
        stub(VerificationReport(listOf(
            RuleVerdict(1, "bez emoji", passed = false, violation = null),
            RuleVerdict(2, "bez przerw między punktami listy", passed = false, violation = "   "),
            RuleVerdict(3, "maksymalnie 8 hashtagów", passed = true)
        )))

        val verdicts = service.verify("Post bez emoji", rules).verdicts

        assertTrue(verdicts.all { it.passed }, "Model, który nie potrafi pokazać naruszenia, go nie znalazł")
    }

    @Test
    fun `brakujacy werdykt nie zamienia sie w naruszenie`() = runBlocking {
        stub(VerificationReport(listOf(
            RuleVerdict(1, "bez emoji", passed = true)
        )))

        val verdicts = service.verify("Post", rules).verdicts

        assertEquals(3, verdicts.size, "Raport ma pokrywać wszystkie reguły")
        assertTrue(verdicts.all { it.passed })
    }

    @Test
    fun `prawdziwe naruszenie nadal jest zglaszane z oryginalna trescia reguly`() = runBlocking {
        stub(VerificationReport(listOf(
            // Model przepisał regułę po swojemu i zmienił wielkość liter — bez znaczenia.
            RuleVerdict(1, "Bez emoji.", passed = false, violation = "emoji 🔥 w pierwszej linii"),
            RuleVerdict(2, "bez przerw między punktami listy", passed = true),
            RuleVerdict(3, "maksymalnie 8 hashtagów", passed = true)
        )))

        val verdicts = service.verify("Świetny post 🔥", rules).verdicts

        assertFalse(verdicts[0].passed)
        assertEquals("bez emoji", verdicts[0].ruleText, "Raport niesie regułę studia, nie parafrazę modelu")
        assertEquals("emoji 🔥 w pierwszej linii", verdicts[0].violation)
    }

    @Test
    fun `dwa werdykty dla tej samej reguly sa niejednoznaczne i nie blokuja posta`() = runBlocking {
        stub(VerificationReport(listOf(
            RuleVerdict(1, "bez emoji", passed = false, violation = "emoji"),
            RuleVerdict(1, "bez emoji", passed = true),
            RuleVerdict(3, "maksymalnie 8 hashtagów", passed = true)
        )))

        val verdicts = service.verify("Post bez emoji", rules).verdicts

        assertTrue(verdicts[0].passed, "Sprzeczne werdykty nie są dowodem naruszenia")
    }
}
