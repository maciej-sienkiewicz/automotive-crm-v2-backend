package pl.detailing.crm.instagram.ai.classification

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import pl.detailing.crm.instagram.ai.model.InstagramPostClassification

/**
 * Klasyfikuje posty Instagramowe za pomocą LLM (OpenAI Structured Outputs).
 *
 * Wyciąga z treści posta:
 *  - [InstagramPostClassification.postTone]       – styl narracyjny (premium/technical/emotional/casual)
 *  - [InstagramPostClassification.serviceType]    – rodzaj usługi detailingowej
 *  - [InstagramPostClassification.carBrand]       – marka samochodu lub "universal"
 *  - [InstagramPostClassification.embeddingText]  – skondensowany opis semantyczny do osadzenia w wektorze
 *
 * Wynik trafia do bazy wektorowej (pgvector) jako metadane dokumentu,
 * co umożliwia warstwowe wyszukiwanie few-shot w [pl.detailing.crm.instagram.ai.inspiration.InstagramInspirationService].
 */
@Service
class InstagramPostClassificationService(
    @Qualifier("instagramChatClient") private val chatClient: ChatClient
) {
    private val logger = LoggerFactory.getLogger(InstagramPostClassificationService::class.java)

    companion object {
        private const val SHORT_POST_THRESHOLD = 200
    }

    /**
     * Klasyfikuje podany tekst posta i zwraca strukturę z metadanymi.
     * Używa `entity()` (OpenAI Structured Outputs) – gwarantuje poprawny JSON.
     *
     * @throws InstagramPostClassificationException gdy LLM zwróci pustą odpowiedź
     */
    suspend fun classify(postContent: String): InstagramPostClassification {
        logger.info("Classifying Instagram post ({} chars): '{}'", postContent.length, postContent.take(80))

        val systemMessage = """
            |Jesteś ekspertem od analizy postów na Instagram z branży car detailing.
            |Przeanalizuj podany post i wyodrębnij z niego metadane.
            |
            |Dostępne wartości:
            |  postTone: premium, technical, emotional, casual
            |    - premium:   elegancki, luksusowy, spokojny, bez wykrzykników, profesjonalny
            |    - technical: merytoryczny, specyfikacje, fakty, liczby, porównania
            |    - emotional: storytelling, emocje, metafory, osobisty, budowanie relacji
            |    - casual:    luźny, potoczny, humorystyczny, emoji, bezpośredni
            |
            |  serviceType: ppf, ceramic, detailing, interior, wrap, polish, other
            |    - ppf:       folia ochronna PPF / Paint Protection Film
            |    - ceramic:   powłoka ceramiczna / ceramic coating
            |    - detailing: ogólny detailing, mycie, czyszczenie, pielęgnacja
            |    - interior:  czyszczenie wnętrza, tapicerka, dezynfekcja
            |    - wrap:      oklejanie / zmiana koloru / folia dekoracyjna / car wrap
            |    - polish:    polerowanie, korekta lakieru, usuwanie rys
            |    - other:     jeśli nie pasuje do żadnej z powyższych kategorii
            |
            |  carBrand: nazwa marki samochodu widoczna w poście
            |    (np. Mercedes, BMW, Porsche, Audi, Ford, Tesla, Lamborghini, Ferrari).
            |    Jeśli brak konkretnej marki → ustaw "universal".
            |
            |  embeddingText: skondensowany opis semantyczny posta (max 10-15 słów kluczowych),
            |    zawierający markę, usługę, kluczowe cechy i ton.
            |    Przykład: "Mercedes-AMG GT BRABUS folia PPF ochrona lakieru realizacja premium"
        """.trimMargin()

        val userMessage = """
            |Sklasyfikuj poniższy post Instagramowy:
            |
            |"$postContent"
        """.trimMargin()

        val result = withContext(Dispatchers.IO) {
            chatClient.prompt()
                .system(systemMessage)
                .user(userMessage)
                .call()
                .entity(InstagramPostClassification::class.java)
        } ?: throw InstagramPostClassificationException(
            "LLM zwrócił pustą odpowiedź przy klasyfikacji posta (${postContent.take(40)}...)"
        )

        logger.info(
            "Post classified: tone={}, service={}, brand={}, embedding='{}'",
            result.postTone, result.serviceType, result.carBrand, result.embeddingText.take(60)
        )
        return result
    }

    /**
     * Oblicza kategorię długości posta na podstawie liczby znaków.
     * <= [SHORT_POST_THRESHOLD] znaków → "short", powyżej → "full".
     */
    fun determinePostLength(content: String): String =
        if (content.length <= SHORT_POST_THRESHOLD) "short" else "full"
}

class InstagramPostClassificationException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
