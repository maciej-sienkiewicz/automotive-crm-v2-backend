package pl.detailing.crm.instagram.ai.generation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import pl.detailing.crm.instagram.ai.model.DebugInstagramPostResult
import pl.detailing.crm.instagram.ai.model.InstagramInspirationContext
import pl.detailing.crm.instagram.ai.model.InstagramPostResult

/**
 * Warstwa Generowania – konstruuje prompt few-shot i wywołuje LLM (OpenAI).
 *
 * Używa `ChatClient.entity()` (OpenAI Structured Outputs) — gwarantuje poprawny JSON
 * zgodny ze schematem [InstagramPostResult] bez ręcznego parsowania.
 *
 * Sekcje promptu systemowego:
 *   1. POSITIVE_EXAMPLES – posty polubione przez studio (wzorzec)
 *   2. NEGATIVE_EXAMPLES – posty odrzucone przez studio (do unikania)
 *   3. TON               – adaptowana do poziomu fallbacku
 *   4. DŁUGOŚĆ           – jeśli podana
 *   5. REGUŁY STYLISTYCZNE – nadrzędne (np. "Nie używaj emoji")
 *   6. INSTRUKCJE        – format: JSON { "content": "..." }
 */
@Service
class InstagramPostGeneratorService(
    @Qualifier("instagramChatClient") private val chatClient: ChatClient
) {
    private val logger = LoggerFactory.getLogger(InstagramPostGeneratorService::class.java)

    /**
     * Generuje post Instagramowy na podstawie tematu i kontekstu inspiracji.
     *
     * @throws InstagramPostGenerationException gdy LLM zwróci pustą odpowiedź
     */
    suspend fun generate(
        topic: String,
        additionalContext: String?,
        inspirationContext: InstagramInspirationContext
    ): InstagramPostResult {
        val systemMessage = buildSystemMessage(inspirationContext)
        val userMessage = buildUserMessage(topic, additionalContext)
        logRequest(topic, inspirationContext)

        val result = withContext(Dispatchers.IO) {
            chatClient.prompt()
                .system(systemMessage)
                .user(userMessage)
                .call()
                .entity(InstagramPostResult::class.java)
        } ?: throw InstagramPostGenerationException(
            "LLM zwrócił pustą odpowiedź dla tematu: '$topic'"
        )

        logger.info("Post generated: content='{}'", result.content.take(80))
        return result
    }

    /**
     * Jak [generate], ale zwraca pełen prompt + wynik (do debugowania i audytu).
     */
    suspend fun generateWithDebug(
        topic: String,
        additionalContext: String?,
        inspirationContext: InstagramInspirationContext
    ): DebugInstagramPostResult {
        val systemMessage = buildSystemMessage(inspirationContext)
        val userMessage = buildUserMessage(topic, additionalContext)
        logRequest(topic, inspirationContext)

        val result = withContext(Dispatchers.IO) {
            chatClient.prompt()
                .system(systemMessage)
                .user(userMessage)
                .call()
                .entity(InstagramPostResult::class.java)
        } ?: throw InstagramPostGenerationException(
            "LLM zwrócił pustą odpowiedź dla tematu: '$topic'"
        )

        logger.info("Post generated (debug): content='{}'", result.content.take(80))

        return DebugInstagramPostResult(
            systemMessage = systemMessage,
            userMessage = userMessage,
            parsed = result,
            inspirationContext = inspirationContext
        )
    }

    // ── Budowanie promptów ────────────────────────────────────────────────────

    private fun buildSystemMessage(context: InstagramInspirationContext): String {
        val positiveSection = if (context.positiveExamples.isNotEmpty()) {
            context.positiveExamples.joinToString(
                separator = "\n",
                prefix = "=== POSITIVE_EXAMPLES (posty, które studio polubiło — wzorzec stylu) ===\n",
                postfix = "\n"
            ) { "- \"$it\"" }
        } else {
            "=== POSITIVE_EXAMPLES ===\nBrak dostępnych przykładów.\n"
        }

        val negativeSection = if (context.negativeExamples.isNotEmpty()) {
            context.negativeExamples.joinToString(
                separator = "\n",
                prefix = "=== NEGATIVE_EXAMPLES (posty, które studio odrzuciło — styl do unikania) ===\n",
                postfix = "\n"
            ) { "- \"$it\"" }
        } else {
            "=== NEGATIVE_EXAMPLES ===\nBrak dostępnych przykładów.\n"
        }

        val toneSection = buildToneSection(context)
        val lengthSection = buildLengthSection(context)
        val styleNotesSection = buildStyleNotesSection(context.styleNotes)

        return """
    |Jesteś profesjonalnym Copywriterem specjalizującym się w branży Automotive i Detailing.
    |Twoim zadaniem jest stworzenie angażującego posta na Instagram, który sprzedaje usługę poprzez korzyści i profesjonalizm.
    |
    |$positiveSection
    |$negativeSection
    |$toneSection
    |$lengthSection
    |$styleNotesSection
    |
    |### TWOJE KLUCZOWE ZADANIA:
    |1. ANALIZA STYLU: Przeanalizuj posty z POSITIVE_EXAMPLES. Zwróć uwagę nie tylko na słowa, ale na to, JAK SĄ UŁOŻONE (gdzie są entery, jak używają list punktowanych).
    |2. STRUKTURA WIZUALNA (KRYTYCZNE): Post MUSI być "napowietrzony" i łatwy do skanowania wzrokiem. 
    |   - Używaj podwójnych znaków nowej linii (Enter) między akapitami.
    |   - Jeśli wymieniasz korzyści/usługi, użyj listy punktowanej (np. z ikonami ✅, ✔️ lub 🛡️).
    |   - Nie twórz "ściany tekstu". Maksimum 2-3 zdania w jednym akapicie.
    |3. KONSTRUKCJA TREŚCI:
    |   - HOOK: Pierwsza linia musi zatrzymywać scrollowanie (pytanie, mocne stwierdzenie lub efekt "wow").
    |   - BODY: Skup się na konkretnym problemie i rozwiązaniu (np. ochrona przed odpryskami, głębia koloru).
    |   - CTA: Jasne wezwanie do działania na końcu (np. "Napisz do nas", "Zarezerwuj termin").
    |4. NEGATIVE EXAMPLES: Jeśli w sekcji NEGATIVE_EXAMPLES posty są zlane w jeden blok, Ty zrób coś przeciwnego. Unikaj ich błędów językowych.
    |
    |### WYMAGANIA TECHNICZNE:
    |- content: Pełny tekst gotowy do publikacji.
    |- Formatowanie: Stosuj entery i spacje tak, aby tekst wyglądał estetycznie na telefonie.
    |- Reguły ze STYLE_NOTES mają najwyższy priorytet (nadpisują styl z przykładów).
    |- Na samym dole dodaj blok 5-8 trafnych hashtagów, oddzielony od reszty tekstu pustą linią.
""".trimMargin()
    }

    private fun buildToneSection(context: InstagramInspirationContext): String {
        val tone = context.requestedTone ?: return ""
        return when {
            context.fallbackInfo.level <= 2 ->
                "=== TON ===\nŻądany ton: $tone. Przykłady POSITIVE już reprezentują ten ton — wzoruj się na nich.\n"
            context.fallbackInfo.level == 3 ->
                "=== TON ===\nŻądany ton: $tone. Przykłady POSITIVE pochodzą z innych studiów, ale są w tym samym tonie — użyj ich jako wzorca.\n"
            else ->
                """
                |=== TON ===
                |Żądany ton: $tone.
                |UWAGA: Przykłady POSITIVE są w INNYM tonie. Traktuj je jako kontekst tematyczny,
                |ale DOSTOSUJ styl do tonu: $tone.
                |Opis tonów:
                |  - premium:   elegancki, luksusowy, spokojny, bez wykrzykników
                |  - technical: merytoryczny, specyfikacje, liczby, fakty
                |  - emotional: storytelling, emocje, metafory, pierwsza osoba
                |  - casual:    luźny, przyjacielski, potoczny, emoji
                """.trimMargin() + "\n"
        }
    }

    private fun buildLengthSection(context: InstagramInspirationContext): String =
        when (context.requestedLength) {
            "short" -> "=== DŁUGOŚĆ ===\nPost powinien być KRÓTKI: hook (1 linijka) + 1-2 zdania treści + CTA. Max 3-4 linijki.\n"
            "full"  -> "=== DŁUGOŚĆ ===\nPost powinien być PEŁNY: hook + 3-5 zdań treści z detalami + CTA. 6-10 linijek.\n"
            else    -> ""
        }

    private fun buildStyleNotesSection(styleNotes: List<String>): String {
        if (styleNotes.isEmpty()) return ""
        val rules = styleNotes.mapIndexed { i, note -> "${i + 1}. $note" }.joinToString("\n")
        return """
            |=== REGUŁY STYLISTYCZNE (NAJWYŻSZY PRIORYTET) ===
            |Reguły są NADRZĘDNE wobec przykładów POSITIVE i NEGATIVE.
            |
            |$rules
        """.trimMargin() + "\n"
    }

    private fun buildUserMessage(topic: String, additionalContext: String?): String {
        val contextPart = if (!additionalContext.isNullOrBlank()) "\nDodatkowy kontekst: $additionalContext" else ""
        return "Stwórz nowy, unikalny post na Instagram dla tematu: \"$topic\"$contextPart"
    }

    private fun logRequest(topic: String, ctx: InstagramInspirationContext) {
        logger.info(
            "Generating post: topic='{}', {} positive, {} negative, tone={}, length={}, fallback={}",
            topic, ctx.positiveExamples.size, ctx.negativeExamples.size,
            ctx.requestedTone, ctx.requestedLength, ctx.fallbackInfo.level
        )
    }
}

class InstagramPostGenerationException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
