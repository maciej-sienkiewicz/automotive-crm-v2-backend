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
 * zgodny ze schematem [InstagramPostResult] bez potrzeby ręcznego parsowania.
 *
 * Sekcje promptu systemowego (w kolejności):
 *   1. POSITIVE_EXAMPLES – posty polubione przez studio (wzorzec do naśladowania)
 *   2. NEGATIVE_EXAMPLES – posty odrzucone przez studio (styl do unikania)
 *   3. TON               – instrukcja tonu (adaptowana do poziomu fallbacku)
 *   4. DŁUGOŚĆ           – instrukcja długości (jeśli podana)
 *   5. REGUŁY STYLISTYCZNE – nadrzędne reguły (np. "Nie używaj emoji")
 *   6. INSTRUKCJE        – finalne wskazówki dla modelu
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

    // ── Budowanie promptów ──────────────────────────────────────────────────────

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
            |Jesteś ekspertem od tworzenia postów na Instagram dla studia z branży car detailing.
            |Twoim zadaniem jest wygenerowanie nowego, unikalnego posta dla tego studia.
            |
            |$positiveSection
            |$negativeSection
            |$toneSection
            |$lengthSection
            |$styleNotesSection
            |INSTRUKCJE:
            |1. Analizuj strukturę, ton i długość postów z sekcji POSITIVE_EXAMPLES — to styl studia.
            |2. Całkowicie unikaj stylu z sekcji NEGATIVE_EXAMPLES (ton, słownictwo, interpunkcja).
            |3. Stwórz nowy, unikalny post dopasowany do tematu podanego przez użytkownika.
            |4. content: pełny tekst posta gotowy do wklejenia na Instagram — jeden spójny blok tekstu
            |   zawierający hook (pierwsza linijka), treść główną i CTA na końcu.
            |   Formatuj tak jak prawdziwe posty: nowe linie, emoji (jeśli pasuje do tonu), hashtagi.
            |5. REGUŁY STYLISTYCZNE (jeśli podane) mają NAJWYŻSZY priorytet — nawet jeśli
            |   przykłady POSITIVE używają emoji, a reguła mówi "nie używaj emoji" — ZASTOSUJ REGUŁĘ.
        """.trimMargin()
    }

    private fun buildToneSection(context: InstagramInspirationContext): String {
        val tone = context.requestedTone ?: return ""
        val fallbackLevel = context.fallbackInfo.level

        return when {
            fallbackLevel <= 2 ->
                // Przykłady pasują tonem — wystarczy potwierdzenie
                "=== TON ===\nŻądany ton: $tone. Przykłady POSITIVE już reprezentują ten ton — wzoruj się na nich.\n"

            fallbackLevel == 3 ->
                // Przykłady globalne, ale w dobrym tonie
                "=== TON ===\nŻądany ton: $tone. Przykłady POSITIVE pochodzą z innych studiów, ale są w tym samym tonie — użyj ich jako wzorca stylu.\n"

            else ->
                // Brak przykładów w żądanym tonie — LLM sam musi trafić w ton
                """
                |=== TON ===
                |Żądany ton: $tone.
                |UWAGA: Przykłady POSITIVE są w INNYM tonie niż żądany. Traktuj je jako kontekst tematyczny
                |(branża, usługa, CTA), ale DOSTOSUJ CAŁKOWICIE styl do tonu: $tone.
                |
                |Opis tonów:
                |  - premium:   elegancki, luksusowy, spokojny, bez wykrzykników, bez emoji-spamu
                |  - technical: merytoryczny, specyfikacje, liczby, fakty, profesjonalny
                |  - emotional: storytelling, emocje, metafory, pierwsza osoba, budowanie relacji
                |  - casual:    luźny, przyjacielski, potoczny, emoji, bezpośredni
                """.trimMargin() + "\n"
        }
    }

    private fun buildLengthSection(context: InstagramInspirationContext): String {
        return when (context.requestedLength) {
            "short" -> "=== DŁUGOŚĆ ===\nPost powinien być KRÓTKI: hook (1 linijka) + 1-2 zdania treści + CTA. Max 3-4 linijki łącznie.\n"
            "full" -> "=== DŁUGOŚĆ ===\nPost powinien być PEŁNY: hook (1 linijka) + 3-5 zdań treści z detalami + CTA. 6-10 linijek łącznie.\n"
            else -> ""
        }
    }

    private fun buildStyleNotesSection(styleNotes: List<String>): String {
        if (styleNotes.isEmpty()) return ""
        val rules = styleNotes.mapIndexed { i, note -> "${i + 1}. $note" }.joinToString("\n")
        return """
            |=== REGUŁY STYLISTYCZNE (NAJWYŻSZY PRIORYTET) ===
            |Poniższe reguły są NADRZĘDNE wobec przykładów POSITIVE i NEGATIVE.
            |Nawet jeśli każdy przykład POSITIVE zawiera emoji lub wykrzykniki,
            |a reguła mówi "nie używaj emoji" — MUSISZ zastosować regułę.
            |
            |$rules
        """.trimMargin() + "\n"
    }

    private fun buildUserMessage(topic: String, additionalContext: String?): String {
        val contextPart = if (!additionalContext.isNullOrBlank()) {
            "\nDodatkowy kontekst: $additionalContext"
        } else ""
        return """
            |Stwórz nowy, unikalny post na Instagram dla tematu: "$topic"
            |$contextPart
        """.trimMargin()
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
