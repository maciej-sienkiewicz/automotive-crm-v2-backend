package pl.detailing.crm.service.taxonomy

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import java.util.UUID

/**
 * Klasyfikator rodzin nazw usług.
 *
 * Dwie własności nośne. Po pierwsze, RAZ NA NAZWĘ: znana nazwa nie dotyka modelu —
 * na tym stoi cała ekonomia funkcji. Po drugie, AWARIA ≠ UNKNOWN: nazwa, dla której
 * model zawiódł, jest w wyniku nieobecna, a nie sklasyfikowana jako „nieznana" —
 * zlanie tych dwóch rzeczy zamroziłoby chwilową awarię API jako wieczny werdykt.
 */
class ServiceFamilyClassifierTest {

    private val chatClient = mockk<ChatClient>()
    private val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
    private val callSpec = mockk<ChatClient.CallResponseSpec>()
    private val repository = mockk<ServiceFamilyRepository>()

    private val userMessages = mutableListOf<String>()
    private val classifier = ServiceFamilyClassifier(chatClient, repository)
    private val studioId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        userMessages.clear()
        every { chatClient.prompt() } returns requestSpec
        every { requestSpec.system(any<String>()) } returns requestSpec
        every { requestSpec.user(capture(userMessages)) } returns requestSpec
        every { requestSpec.call() } returns callSpec
        every { repository.findByStudioIdInAndNameKeyIn(any(), any()) } returns emptyList()
        every { repository.save(any()) } answers { firstArg() }
    }

    private fun stub(vararg answers: ServiceFamilyClassifier.RawAnswer) {
        every { callSpec.entity(ServiceFamilyClassifier.RawAnswers::class.java) } returns
            ServiceFamilyClassifier.RawAnswers(answers.toList())
    }

    @Test
    fun `znana nazwa nie dotyka modelu`() {
        every { repository.findByStudioIdInAndNameKeyIn(any(), any()) } returns listOf(
            row(ServiceFamilyEntity.GLOBAL_STUDIO, "powłoka ceramiczna", "CERAMIC_COATING", "UNKNOWN")
        )

        val result = classifier.classify(studioId, listOf("Powłoka ceramiczna"))

        assertEquals(ServiceFamily.CERAMIC_COATING, result.getValue("powłoka ceramiczna").family)
        verify(exactly = 0) { chatClient.prompt() }
    }

    @Test
    fun `poprawka studia wygrywa z wierszem globalnym`() {
        every { repository.findByStudioIdInAndNameKeyIn(any(), any()) } returns listOf(
            row(ServiceFamilyEntity.GLOBAL_STUDIO, "ochrona lakieru", "PPF", "UNKNOWN"),
            row(studioId, "ochrona lakieru", "CERAMIC_COATING", "UNKNOWN")
        )

        val result = classifier.classify(studioId, listOf("Ochrona lakieru"))

        assertEquals(ServiceFamily.CERAMIC_COATING, result.getValue("ochrona lakieru").family)
    }

    @Test
    fun `nowa nazwa idzie do modelu numerowana i wraca zapisana`() {
        stub(ServiceFamilyClassifier.RawAnswer(1, "PPF", "PARTIAL"))

        val result = classifier.classify(studioId, listOf("Oklejenie przodu PPF"))

        assertTrue(userMessages.single().contains("1. Oklejenie przodu PPF"))
        assertEquals(ServiceFamily.PPF, result.getValue("oklejenie przodu ppf").family)
        assertEquals(ServiceScope.PARTIAL, result.getValue("oklejenie przodu ppf").scope)
        verify(exactly = 1) { repository.save(any()) }
    }

    @Test
    fun `awaria modelu to nieobecnosc, nie unknown`() {
        every { callSpec.entity(ServiceFamilyClassifier.RawAnswers::class.java) } throws IllegalStateException("timeout")

        val result = classifier.classify(studioId, listOf("Oklejenie przodu PPF"))

        assertFalse("oklejenie przodu ppf" in result, "Nieobecność znaczy „spróbuj ponownie” — UNKNOWN byłby wiecznym werdyktem")
        verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `kod spoza taksonomii laduje jako unknown, nie jako wyjatek`() {
        stub(ServiceFamilyClassifier.RawAnswer(1, "ZMYSLONA_RODZINA", "FULL"))

        val result = classifier.classify(studioId, listOf("Pakiet 3"))

        assertEquals(ServiceFamily.UNKNOWN, result.getValue("pakiet 3").family)
    }

    @Test
    fun `prompt rozdziela ppf od wrapu i zakazuje zgadywania`() {
        val prompt = ServiceFamilyClassifier.SYSTEM_PROMPT

        assertTrue(prompt.contains("PPF i WRAP to DWIE RÓŻNE rodziny"))
        assertTrue(prompt.contains("Nie zgaduj"))
        assertTrue(prompt.contains("UNKNOWN"))
    }

    private fun row(studio: UUID, nameKey: String, family: String, scope: String) =
        ServiceFamilyEntity(
            studioId = studio, nameKey = nameKey, nameSample = nameKey,
            family = family, scope = scope
        )
}
