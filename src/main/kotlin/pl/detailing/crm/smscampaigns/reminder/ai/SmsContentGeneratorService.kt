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
    val daysSinceService: Int
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

        logger.info(
            "Generating SMS reminder: customer={} vehicle={} {} services={}",
            context.customerFirstName, context.vehicleBrand, context.vehicleModel,
            context.services.size
        )

        val raw = withContext(Dispatchers.IO) {
            chatClient.prompt()
                .system(systemPrompt)
                .user(userPrompt)
                .call()
                .content()
        } ?: throw SmsContentGenerationException("LLM zwrócił pustą odpowiedź")

        val content = raw.trim()
            .removePrefix("\"").removeSuffix("\"")
            .trim()

        if (content.length > 160) {
            logger.warn("Generated SMS exceeds 160 chars ({}), truncating", content.length)
        }

        logger.info("SMS generated ({} chars): '{}'", content.length, content.take(60))
        return content
    }

    // ── Prompt ──────────────────────────────────────────────────────────────────

    private fun buildSystemPrompt() = """
        Jesteś ekspertem od copywritingu SMS dla branży automotive detailing w Polsce.
        Tworzysz krótkie, ciepłe i skuteczne wiadomości SMS zachęcające klienta do powrotu.

        BEZWZGLĘDNE ZASADY:
        1. Język: wyłącznie polski — poprawna polszczyzna, bez błędów ortograficznych.
        2. Długość: MAKSYMALNIE 160 znaków (jeden segment SMS). Licz znaki skrupulatnie.
        3. Ton: ciepły, osobisty, profesjonalny. Zwracaj się do klienta po imieniu.
        4. Treść: nawiąż do konkretnych wykonanych usług. Zakończ miękkim CTA (np. "Zapraszamy!").
        5. Zakaz: nie umieszczaj URL-i, numerów telefonu, adresów e-mail.
        6. Format: zwróć WYŁĄCZNIE tekst SMS — żadnych cudzysłowów, nagłówków ani komentarzy.

        PRZYKŁADY (wzorcowe):
        - "Cześć Marek! Minęły 3 mies. od Twojego powlekania ceramicznego w AutoDetailing Pro. Czas na inspekcję i odświeżenie ochrony? Zapraszamy!"
        - "Hej Ania! Twój BMW był u nas w sierpniu na korekcie lakieru. Jak trzyma połysk? Chętnie zadbamy o niego ponownie. AutoSpa"
        - "Marcin, 90 dni temu dbaliśmy o Twojego Audiego. Sezon zimowy tuż-tuż — może warto go odpowiednio przygotować? Czekamy na Ciebie!"
    """.trimIndent()

    private fun buildUserPrompt(ctx: SmsGenerationContext): String {
        val vehicleDesc = buildString {
            append(ctx.vehicleBrand).append(" ").append(ctx.vehicleModel)
            ctx.licensePlate?.let { append(" ($it)") }
        }
        val servicesList = ctx.services.joinToString(", ")
        val timeDesc = when {
            ctx.daysSinceService < 60 -> "${ctx.daysSinceService} dni"
            ctx.daysSinceService < 365 -> "${ctx.daysSinceService / 30} miesięcy"
            else -> "${ctx.daysSinceService / 365} roku"
        }

        return """
            Napisz SMS przypomnienie dla klienta.

            DANE KLIENTA:
            - Imię: ${ctx.customerFirstName}
            - Pojazd: $vehicleDesc
            - Studio: ${ctx.studioName}
            - Wykonane usługi: $servicesList
            - Od wizyty minęło: $timeDesc

            Stwórz osobisty, zachęcający SMS (max 160 znaków).
        """.trimIndent()
    }
}

class SmsContentGenerationException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
