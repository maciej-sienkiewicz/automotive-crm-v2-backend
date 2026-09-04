package pl.detailing.crm.leads.classification

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient

/**
 * Odpowiedź modelu przechodzi przez ten kod, zanim zdecyduje o powstaniu leada.
 * Wszystko, co model może zwrócić — także coś, czego nie powinien — musi się tu
 * skończyć jednym z dwóch stanów: zrozumiałym werdyktem albo czystym „nie wiemy”.
 */
class LeadMessageClassifierTest {

    private val chatClient = mockk<ChatClient>()
    private val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
    private val callSpec = mockk<ChatClient.CallResponseSpec>()

    private val classifier = LeadMessageClassifier(chatClient, "gpt-4o-mini")

    @BeforeEach
    fun setUp() {
        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.system(any<String>()) } returns requestSpec
        every { requestSpec.user(any<String>()) } returns requestSpec
        every { requestSpec.call() } returns callSpec
    }

    private fun stub(verdict: String?, confidence: Double?, reasoning: String? = null) {
        every { callSpec.entity(LeadMessageClassifier.RawVerdict::class.java) } returns
            LeadMessageClassifier.RawVerdict(verdict, confidence, reasoning)
    }

    @Test
    fun `czyta werdykt LEAD z pewnoscia i uzasadnieniem`() = runBlocking {
        stub("LEAD", 0.92, "klient pyta o cenę PPF na własne auto")

        val result = classifier.classify("Wycena", "Ile kosztuje oklejenie BMW M3?")!!

        assertEquals(LeadClassificationVerdict.LEAD, result.verdict)
        assertEquals(0.92, result.confidence)
        assertEquals("klient pyta o cenę PPF na własne auto", result.reasoning)
    }

    @Test
    fun `przyjmuje polska i angielska nazwe klasy negatywnej`() = runBlocking {
        // Prompt zamawia „NIE_LEAD”, ale model bywa usłużny i tłumaczy etykiety.
        // Odrzucenie własnej odpowiedzi modelu przez literówkę w języku byłoby
        // najgłupszym z możliwych powodów, żeby nie utworzyć leada.
        stub("NIE_LEAD", 0.88)
        assertEquals(LeadClassificationVerdict.NOT_LEAD, classifier.classify(null, "oferta folii")!!.verdict)

        stub("not_lead", 0.88)
        assertEquals(LeadClassificationVerdict.NOT_LEAD, classifier.classify(null, "oferta folii")!!.verdict)
    }

    @Test
    fun `pewnosc podana w procentach wraca do skali 0-1`() = runBlocking {
        // 95 zamiast 0.95 przeszłoby każdy próg i wyłączyło filtrowanie po cichu.
        stub("LEAD", 95.0)

        assertEquals(0.95, classifier.classify(null, "Ile za ceramikę?")!!.confidence)
    }

    @Test
    fun `pewnosc poza zakresem jest przycinana`() = runBlocking {
        stub("LEAD", -3.0)
        assertEquals(0.0, classifier.classify(null, "cokolwiek")!!.confidence)
    }

    @Test
    fun `brak pewnosci w odpowiedzi to zero, a nie domyslna zgoda`() = runBlocking {
        // Pole puste znaczy „model nie ocenił”. Domyślna 1.0 tworzyłaby leada
        // dokładnie wtedy, gdy wiemy o sprawie najmniej.
        stub("LEAD", null)

        assertEquals(0.0, classifier.classify(null, "Ile za ceramikę?")!!.confidence)
    }

    @Test
    fun `nierozpoznany werdykt to brak odpowiedzi, nie nie-lead`() = runBlocking {
        stub("MOŻE", 0.9)

        assertNull(
            classifier.classify(null, "Ile za ceramikę?"),
            "„Nie wiemy” i „to nie jest lead” trafiają do dziennika jako inne statusy"
        )
    }

    @Test
    fun `pusta tresc nie uruchamia modelu`() = runBlocking {
        val result = classifier.classify("Temat", "   ")

        assertNull(result)
        verify(exactly = 0) { chatClient.prompt() }
    }

    @Test
    fun `blad przejsciowy jest ponawiany i moze sie udac`() = runBlocking {
        var attempts = 0
        every { callSpec.entity(LeadMessageClassifier.RawVerdict::class.java) } answers {
            attempts++
            if (attempts == 1) throw RuntimeException("HTTP 429 Too Many Requests")
            LeadMessageClassifier.RawVerdict("LEAD", 0.9, null)
        }

        val result = classifier.classify(null, "Ile za korektę lakieru?")

        assertEquals(LeadClassificationVerdict.LEAD, result?.verdict)
        assertEquals(2, attempts, "Limit zapytań to typowa chwilowa awaria — druga próba zwykle przechodzi")
    }

    @Test
    fun `blad trwaly nie jest ponawiany`() = runBlocking {
        var attempts = 0
        every { callSpec.entity(LeadMessageClassifier.RawVerdict::class.java) } answers {
            attempts++
            throw RuntimeException("HTTP 400 invalid_request_error")
        }

        assertNull(classifier.classify(null, "Ile za korektę lakieru?"))
        assertEquals(
            1, attempts,
            "Powtarzanie błędnego żądania tylko zajmuje wątek puli, która ma do przerobienia resztę poczty"
        )
    }

    @Test
    fun `awaria modelu konczy sie brakiem odpowiedzi, nie wyjatkiem`() = runBlocking {
        every { callSpec.entity(LeadMessageClassifier.RawVerdict::class.java) } throws
            RuntimeException("connection reset by peer")

        // Wyjątek wychodzący stąd przerwałby przetwarzanie maila; „nie wiemy”
        // zostaje zapisane w dzienniku jako FAILED i wiadomość czeka na człowieka.
        assertNull(classifier.classify(null, "Ile za korektę lakieru?"))
    }
}
