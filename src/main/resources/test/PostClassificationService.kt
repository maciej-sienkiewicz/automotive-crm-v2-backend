package com.example.demo.adcopy.service

import com.example.demo.adcopy.model.PostClassification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.stereotype.Service

/**
 * Klasyfikuje posty na Instagram za pomocą LLM.
 * Wyciąga: postTone, serviceType, carBrand, embeddingText.
 * Używa OpenAI Structured Outputs przez ChatClient.entity() —
 * gwarantuje poprawny JSON zgodny ze schematem PostClassification.
 */
@Service
class PostClassificationService(
    private val chatClient: ChatClient
) {
    private val logger = LoggerFactory.getLogger(PostClassificationService::class.java)

    companion object {
        private const val SHORT_THRESHOLD = 200
    }

    suspend fun classify(postContent: String): PostClassification {
        logger.info("Classifying post ({} chars): '{}'", postContent.length, postContent.take(80))

        val systemMessage = """
            |Jesteś ekspertem od analizy postów na Instagram z branży car detailing.
            |Przeanalizuj podany post i wyodrębnij z niego metadane.
            |
            |Dostępne wartości:
            |  postTone: premium, technical, emotional, casual
            |    - premium: elegancki, luksusowy, spokojny
            |    - technical: merytoryczny, specyfikacje, fakty, liczby
            |    - emotional: storytelling, emocje, metafory, osobisty
            |    - casual: luźny, potoczny, humorystyczny
            |
            |  serviceType: ppf, ceramic, detailing, interior, wrap, polish, other
            |    - ppf: folia ochronna PPF / Paint Protection Film
            |    - ceramic: powłoka ceramiczna / ceramic coating
            |    - detailing: ogólny detailing, mycie, czyszczenie
            |    - interior: czyszczenie wnętrza, tapicerka
            |    - wrap: oklejanie zmiana koloru / car wrap / folia dekoracyjna
            |    - polish: polerowanie, korekta lakieru
            |    - other: jeśli nie pasuje do żadnej z powyższych
            |
            |  carBrand: nazwa marki samochodu widoczna w poście (np. Mercedes, BMW, Porsche, Audi, Ford, Tesla).
            |    Jeśli brak konkretnej marki, ustaw "universal".
            |
            |  embeddingText: skondensowany opis semantyczny posta (max 10-15 słów kluczowych),
            |    np. "Mercedes-AMG GT BRABUS oklejanie folią PPF ochrona lakieru realizacja premium".
            |    Ma zawierać: markę, model (jeśli jest), usługę, kluczowe cechy, ton.
        """.trimMargin()

        val userMessage = """
            |Sklasyfikuj poniższy post:
            |
            |"$postContent"
        """.trimMargin()

        val result = withContext(Dispatchers.IO) {
            chatClient.prompt()
                .system(systemMessage)
                .user(userMessage)
                .call()
                .entity(PostClassification::class.java)
        } ?: throw LlmEmptyResponseException("LLM zwrócił pustą odpowiedź przy klasyfikacji posta")

        logger.info(
            "Post classified: tone={}, service={}, brand={}, embedding='{}'",
            result.postTone, result.serviceType, result.carBrand, result.embeddingText.take(60)
        )
        return result
    }

    /**
     * Oblicza postLength na podstawie długości tekstu.
     * <= 200 znaków → "short", > 200 → "full"
     */
    fun determinePostLength(content: String): String {
        return if (content.length <= SHORT_THRESHOLD) "short" else "full"
    }
}
