package pl.detailing.crm.smscampaigns.reminder.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

data class SmsGenerationContext(
    val customerFirstName: String,
    val vehicleBrand: String,
    val vehicleModel: String,
    val licensePlate: String?,
    val services: List<String>,
    val studioName: String,
    val daysSinceService: Int,
    val phoneNumber: String
)

/**
 * Calls the LLM (OpenAI via Spring AI) to draft a personalised retention SMS.
 *
 * The prompt instructs the model to act as a Polish automotive detailing copywriter.
 * Key constraints:
 *  - Polish language only
 *  - Max 160 characters (one SMS segment)
 *  - Personal, warm tone — address customer by first name
 *  - Mention specific services performed
 *  - Include a soft call-to-action (book next appointment)
 *  - No URLs, no phone numbers (those are added by the studio separately)
 */
@Service
class SmsContentGeneratorService(
    @Qualifier("smsReminderChatClient") private val chatClient: ChatClient
) {
    private val logger = LoggerFactory.getLogger(SmsContentGeneratorService::class.java)

    suspend fun generate(context: SmsGenerationContext): String {
        val systemPrompt = buildSystemPrompt()
        val userPrompt = buildUserPrompt(context)

        val raw = withContext(Dispatchers.IO) {
            chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content()
        } ?: throw SmsContentGenerationException("LLM zwrócił pustą odpowiedź")

        return raw.trim().removePrefix("\"").removeSuffix("\"").trim()
    }

    private fun buildSystemPrompt() = """
    ROLA: 
    Jesteś Ekspertem Concierge w luksusowym studio detailingu. Twoim zadaniem jest dbanie o trwałość inwestycji klienta i utrzymywanie standardu premium.

    ZASADY KRYTYCZNE:
    1. TYTULATURA: Zawsze "Panie [Imię]". Nigdy "Cześć", "Hej" ani samo imię.
    2. ZAKAZ KICZU: Nie personifikuj auta ("auto tęskni"), nie pytaj o emocje klienta ("jak się Pan czuje?").
    3. ZERO RYTUALNYCH FRAZ: Unikaj nadużywania słowa "dekontaminacja", "hydrofobowość" czy "korekta", chyba że są niezbędne dla konkretnej porady. Pisz o "standardzie", "kondycji lakieru", "estetyce" lub "ochronie kapitału".
    4. KOMUNIKACJA ONE-WAY: Nie zadawaj pytań retorycznych. Pisz twierdzeniami eksperckimi.
    5. JĘZYK KORZYŚCI: Skup się na tym, co klient ZYSKUJE (trwałość, duma, czas) lub co MOŻE STRACIĆ (wartość auta, fabryczny wygląd) przez czynniki zewnętrzne.

    METODA ROTACJI (Wybierz LOSOWO jeden z archetypów dla każdej wiadomości):
    - Archetyp OPIEKUNA: Skup się na ochronie inwestycji i bezpieczeństwie powierzchni przed naturą (UV, sól, woda).
    - Archetyp WŁADCY: Skup się na utrzymaniu najwyższego standardu, prestiżu i nienagannego wyglądu, który wyróżnia auto.
    - Archetyp MĘDRCA: Podaj konkretną, krótką poradę serwisową bez nachalnej sprzedaży (edukacja).

    LIMIT: Max 300 znaków. Używaj polskich znaków.
""".trimIndent()

    private fun buildUserPrompt(ctx: SmsGenerationContext): String {
        val servicesString = ctx.services.joinToString(", ")
        return """
        KONTEKST:
        Klient: ${ctx.customerFirstName}
        Pojazd: ${ctx.vehicleBrand} ${ctx.vehicleModel}
        Wykonane usługi: $servicesString
        Czas od wizyty: ${ctx.daysSinceService} dni
        Numer telefonu: ${ctx.phoneNumber}
        
        ZADANIE:
        Wygeneruj unikalny, profesjonalny komunikat SMS. 
        Uwzględnij porę roku lub wpływ środowiska (pogoda, kurz, osady) na auto po usłudze $servicesString. 
        Wyjaśnij, dlaczego profesjonalna kontrola lub mycie serwisowe po ${ctx.daysSinceService} dniach jest strategicznie lepsze dla auta niż przypadkowa pielęgnacja LUB zachęć do odnowienia lub dodatkowej usługi.

        INSTRUKCJA ANTY-REPETALNA:
        Nie używaj sformułowań z poprzednich generacji. Spróbuj użyć innej metafory (np. zegarmistrzowska precyzja, SPA dla technologii, ochrona kapitału). 
    """.trimIndent()
    }

    private fun formatTime(days: Int): String = when {
        days < 31 -> "$days dni"
        days < 365 -> "${days / 30} m-cy"
        else -> "ponad rok"
    }
}

class SmsContentGenerationException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
