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
import pl.detailing.crm.instagram.ai.model.RuleVerdict
import pl.detailing.crm.instagram.ai.model.VerificationReport
import pl.detailing.crm.instagram.ai.verification.InstagramPostVerifierService
import pl.detailing.crm.instagram.ai.verification.StyleRuleChecker

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

    private val service = InstagramPostVerifierService(chatClient, StyleRuleChecker())

    /**
     * Wyłącznie reguły JAKOŚCIOWE: policzalne („bez emoji", „3 bullet pointy") rozstrzyga
     * teraz StyleRuleChecker i nigdy nie trafiają do modelu — patrz [StyleRuleCheckerTest].
     */
    private val rules = listOf(
        "Pisz ciepłym, bezpośrednim tonem",
        "Nie obiecuj efektów bez pokrycia",
        "Zakończ wezwaniem do działania"
    )

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
            RuleVerdict(0, "Pisz ciepłym, bezpośrednim tonem", passed = true),
            RuleVerdict(1, "Nie obiecuj efektów bez pokrycia", passed = false, violation = "obietnica 100% efektu"),
            RuleVerdict(2, "Zakończ wezwaniem do działania", passed = true)
        )))

        val verdicts = service.verify("Dowolny post", rules).verdicts

        assertTrue(verdicts[0].passed, "Reguła o tonie nie może przejąć cudzego naruszenia")
        assertFalse(verdicts[1].passed)
        assertEquals("obietnica 100% efektu", verdicts[1].violation)
        assertTrue(verdicts[2].passed)
    }

    @Test
    fun `werdykty w innej kolejnosci trafiaja do wlasciwych regul po tresci`() = runBlocking {
        stub(VerificationReport(listOf(
            RuleVerdict(1, "Zakończ wezwaniem do działania", passed = false, violation = "post kończy się hashtagami"),
            RuleVerdict(2, "Pisz ciepłym, bezpośrednim tonem", passed = true),
            RuleVerdict(3, "Nie obiecuj efektów bez pokrycia", passed = true)
        )))

        val verdicts = service.verify("Post", rules).verdicts

        assertTrue(verdicts[0].passed, "«bez emoji» dostało numer 2, ale treść mówi, czyj to werdykt")
        assertTrue(verdicts[1].passed)
        assertFalse(verdicts[2].passed)
        assertEquals("post kończy się hashtagami", verdicts[2].violation)
    }

    @Test
    fun `naruszenie bez wskazania fragmentu nie jest naruszeniem`() = runBlocking {
        stub(VerificationReport(listOf(
            RuleVerdict(1, "Pisz ciepłym, bezpośrednim tonem", passed = false, violation = null),
            RuleVerdict(2, "Nie obiecuj efektów bez pokrycia", passed = false, violation = "   "),
            RuleVerdict(3, "Zakończ wezwaniem do działania", passed = true)
        )))

        val verdicts = service.verify("Dowolny post", rules).verdicts

        assertTrue(verdicts.all { it.passed }, "Model, który nie potrafi pokazać naruszenia, go nie znalazł")
    }

    @Test
    fun `brakujacy werdykt nie zamienia sie w naruszenie`() = runBlocking {
        stub(VerificationReport(listOf(
            RuleVerdict(1, "Pisz ciepłym, bezpośrednim tonem", passed = true)
        )))

        val verdicts = service.verify("Post", rules).verdicts

        assertEquals(3, verdicts.size, "Raport ma pokrywać wszystkie reguły")
        assertTrue(verdicts.all { it.passed })
    }

    @Test
    fun `prawdziwe naruszenie nadal jest zglaszane z oryginalna trescia reguly`() = runBlocking {
        stub(VerificationReport(listOf(
            // Model przepisał regułę po swojemu i zmienił wielkość liter — bez znaczenia.
            RuleVerdict(1, "Pisz ciepłym, bezpośrednim tonem.", passed = false, violation = "urzędowy zwrot uprzejmie informujemy"),
            RuleVerdict(2, "bez przerw między punktami listy", passed = true),
            RuleVerdict(3, "Zakończ wezwaniem do działania", passed = true)
        )))

        val verdicts = service.verify("Uprzejmie informujemy o ofercie", rules).verdicts

        assertFalse(verdicts[0].passed)
        assertEquals("Pisz ciepłym, bezpośrednim tonem", verdicts[0].ruleText, "Raport niesie regułę studia, nie parafrazę modelu")
        assertEquals("urzędowy zwrot uprzejmie informujemy", verdicts[0].violation)
    }

    @Test
    fun `dwa werdykty dla tej samej reguly sa niejednoznaczne i nie blokuja posta`() = runBlocking {
        stub(VerificationReport(listOf(
            RuleVerdict(1, "Pisz ciepłym, bezpośrednim tonem", passed = false, violation = "ton urzędowy"),
            RuleVerdict(1, "Pisz ciepłym, bezpośrednim tonem", passed = true),
            RuleVerdict(3, "Zakończ wezwaniem do działania", passed = true)
        )))

        val verdicts = service.verify("Dowolny post", rules).verdicts

        assertTrue(verdicts[0].passed, "Sprzeczne werdykty nie są dowodem naruszenia")
    }

    @Test
    fun `regula policzalna nie trafia do modelu i wygrywa z jego werdyktem`() = runBlocking {
        val countable = listOf("bez emoji", "3 bullet pointy")
        // Gdyby model dostał te reguły, „naruszyłby" obie — a tekst spełnia jedną i drugą.
        stub(VerificationReport(listOf(
            RuleVerdict(1, "bez emoji", passed = false, violation = "brak emoji w poście"),
            RuleVerdict(2, "3 bullet pointy", passed = false, violation = "4 bullet pointy")
        )))

        val post = "Zalety:\n- pierwsza\n- druga\n- trzecia\n\nZapraszamy."
        val verdicts = service.verify(post, countable).verdicts

        assertTrue(verdicts.all { it.passed }, "Liczy kod, nie model: ${verdicts.map { it.violation }}")
        verify(exactly = 0) { chatClient.prompt() }
    }
}
