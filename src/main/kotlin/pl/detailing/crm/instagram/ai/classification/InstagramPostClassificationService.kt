package pl.detailing.crm.instagram.ai.classification

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import pl.detailing.crm.instagram.ai.infrastructure.OpenAiClient
import pl.detailing.crm.instagram.ai.model.InstagramPostClassification

/**
 * Klasyfikuje posty Instagramowe za pomocą LLM (OpenAI JSON mode).
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
    private val openAiClient: OpenAiClient
) {
    private val logger = LoggerFactory.getLogger(InstagramPostClassificationService::class.java)

    companion object {
        private const val SHORT_POST_THRESHOLD = 200
    }

    /**
     * Klasyfikuje podany tekst posta i zwraca strukturę z metadanymi.
     *
     * @throws InstagramPostClassificationException gdy LLM zwróci pustą lub nieparsowalnną odpowiedź
     */
    suspend fun classify(postContent: String): InstagramPostClassification {
        logger.info("Classifying Instagram post ({} chars): '{}'", postContent.length, postContent.take(80))

        val systemMessage = """
            |Jesteś ekspertem od analizy postów na Instagram z branży car detailing.
            |Przeanalizuj podany post i wyodrębnij metadane.
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
            |    - other:     nie pasuje do żadnej z powyższych kategorii
            |
            |  carBrand: marka samochodu widoczna w poście (np. Mercedes, BMW, Porsche, Audi, Ford, Tesla).
            |    Jeśli brak konkretnej marki → "universal".
            |
            |  embeddingText: skondensowany opis semantyczny (max 10-15 słów kluczowych)
            |    zawierający markę, usługę, kluczowe cechy i ton.
            |    Przykład: "Mercedes-AMG GT BRABUS folia PPF ochrona lakieru realizacja premium"
            |
            |Zwróć odpowiedź WYŁĄCZNIE jako JSON (bez żadnego dodatkowego tekstu):
            |{
            |  "postTone": "...",
            |  "serviceType": "...",
            |  "carBrand": "...",
            |  "embeddingText": "..."
            |}
        """.trimMargin()

        val userMessage = "Sklasyfikuj poniższy post Instagramowy:\n\n\"$postContent\""

        val result = try {
            openAiClient.chatStructured(systemMessage, userMessage, InstagramPostClassification::class.java)
        } catch (e: Exception) {
            throw InstagramPostClassificationException(
                "Błąd klasyfikacji posta (${postContent.take(40)}...): ${e.message}", e
            )
        }

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
