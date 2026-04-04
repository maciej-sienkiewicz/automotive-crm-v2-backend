package com.example.demo.adcopy.service

import com.example.demo.adcopy.model.AdCopyResponse
import com.example.demo.adcopy.model.DebugAdCopyResponse
import com.example.demo.adcopy.model.InspirationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.stereotype.Service

/**
 * Warstwa Generowania – konstruuje prompt Few-Shot i wywołuje LLM.
 * Używa ChatClient.entity() (OpenAI Structured Outputs) — gwarantuje
 * poprawny JSON zgodny ze schematem AdCopyResponse.
 */
@Service
class AdGeneratorService(
    private val chatClient: ChatClient
) {
    private val logger = LoggerFactory.getLogger(AdGeneratorService::class.java)

    /**
     * Generuje post — używa entity() (structured output, gwarantowany format).
     */
    suspend fun generate(
        topic: String,
        additionalContext: String?,
        inspirationContext: InspirationContext
    ): AdCopyResponse {
        val systemMessage = buildSystemMessage(inspirationContext)
        val userMessage = buildUserMessage(topic, additionalContext)
        logRequest(topic, inspirationContext)

        val result = withContext(Dispatchers.IO) {
            chatClient.prompt()
                .system(systemMessage)
                .user(userMessage)
                .call()
                .entity(AdCopyResponse::class.java)
        } ?: throw LlmEmptyResponseException("LLM zwrócił pustą odpowiedź dla tematu: $topic")

        logger.info("Generated: headline='{}'", result.headline)
        return result
    }

    /**
     * Generuje post z debugiem — zwraca prompt + wynik.
     * Również używa entity() (structured output).
     */
    suspend fun generateWithDebug(
        topic: String,
        additionalContext: String?,
        inspirationContext: InspirationContext
    ): DebugAdCopyResponse {
        val systemMessage = buildSystemMessage(inspirationContext)
        val userMessage = buildUserMessage(topic, additionalContext)
        logRequest(topic, inspirationContext)

        val result = withContext(Dispatchers.IO) {
            chatClient.prompt()
                .system(systemMessage)
                .user(userMessage)
                .call()
                .entity(AdCopyResponse::class.java)
        } ?: throw LlmEmptyResponseException("LLM zwrócił pustą odpowiedź dla tematu: $topic")

        logger.info("Generated (debug): headline='{}'", result.headline)

        return DebugAdCopyResponse(
            systemMessage = systemMessage,
            userMessage = userMessage,
            rawLlmResponse = "(structured output — JSON gwarantowany przez OpenAI)",
            parsed = result,
            inspirationContext = inspirationContext
        )
    }

    private fun logRequest(topic: String, ctx: InspirationContext) {
        logger.info(
            "Generating: topic='{}', {} positive, {} negative, tone={}, length={}, fallback={}",
            topic, ctx.positiveExamples.size, ctx.negativeExamples.size,
            ctx.requestedTone, ctx.requestedLength, ctx.fallbackInfo.level
        )
    }

    // ── Budowanie promptów ──────────────────────────────────────

    private fun buildSystemMessage(context: InspirationContext): String {
        val positiveSection = if (context.positiveExamples.isNotEmpty()) {
            context.positiveExamples.joinToString(
                separator = "\n",
                prefix = "=== POSITIVE_EXAMPLES (posty, które użytkownik polubił) ===\n",
                postfix = "\n"
            ) { "- \"$it\"" }
        } else {
            "=== POSITIVE_EXAMPLES ===\nBrak dostępnych przykładów.\n"
        }

        val negativeSection = if (context.negativeExamples.isNotEmpty()) {
            context.negativeExamples.joinToString(
                separator = "\n",
                prefix = "=== NEGATIVE_EXAMPLES (posty, które użytkownik odrzucił) ===\n",
                postfix = "\n"
            ) { "- \"$it\"" }
        } else {
            "=== NEGATIVE_EXAMPLES ===\nBrak dostępnych przykładów.\n"
        }

        // Instrukcja tonu — kluczowa gdy fallback > 2 (przykłady nie pasują tonem)
        val toneInstruction = buildToneInstruction(context)

        // Instrukcja długości
        val lengthInstruction = buildLengthInstruction(context)

        // Reguły stylistyczne — nadrzędne wobec przykładów
        val styleNotesSection = buildStyleNotesSection(context.styleNotes)

        return """
            |Jesteś ekspertem od tworzenia postów na Instagram dla firmy z branży car detailing.
            |Twoim zadaniem jest wygenerowanie nowego, unikalnego posta.
            |
            |$positiveSection
            |$negativeSection
            |$toneInstruction
            |$lengthInstruction
            |$styleNotesSection
            |INSTRUKCJE:
            |1. Analizuj strukturę, ton i długość postów z sekcji POSITIVE_EXAMPLES.
            |2. Całkowicie unikaj stylu z sekcji NEGATIVE_EXAMPLES.
            |3. Stwórz nowy, unikalny post dopasowany do tematu podanego przez użytkownika.
            |4. headline: chwytliwy nagłówek posta (1 linijka).
            |5. description: główna treść posta (z uwzględnieniem reguł stylistycznych).
            |6. punchline: krótkie CTA na końcu.
            |7. REGUŁY STYLISTYCZNE (jeśli podane) mają NAJWYŻSZY priorytet — nawet jeśli
            |   przykłady POSITIVE używają emoji, a reguła mówi "nie używaj emoji" — ZASTOSUJ REGUŁĘ.
        """.trimMargin()
    }

    private fun buildToneInstruction(context: InspirationContext): String {
        val tone = context.requestedTone ?: return ""
        val fallbackLevel = context.fallbackInfo.level

        return when {
            fallbackLevel <= 2 -> {
                // Przykłady pasują tonem — wystarczy wzmocnienie
                "=== TON ===\nŻądany ton: $tone. Przykłady POSITIVE już pasują do tego tonu — wzoruj się na nich.\n"
            }
            fallbackLevel == 3 -> {
                // Przykłady są globalne (inny user) ale w dobrym tonie
                "=== TON ===\nŻądany ton: $tone. Przykłady POSITIVE pochodzą od innych użytkowników w tym tonie — użyj ich jako wzorca stylu.\n"
            }
            else -> {
                // Brak przykładów w żądanym tonie — LLM musi sam dopasować
                """
                |=== TON ===
                |Żądany ton: $tone.
                |UWAGA: Przykłady POSITIVE są w INNYM tonie niż żądany. Traktuj je jako kontekst preferencji
                |użytkownika (marka, usługa, CTA), ale DOSTOSUJ styl do tonu: $tone.
                |Opis tonów:
                |  - premium: elegancki, luksusowy, spokojny, bez wykrzykników
                |  - technical: merytoryczny, specyfikacje, fakty, profesjonalny
                |  - emotional: storytelling, emocje, metafory, osobisty
                |  - casual: luźny, przyjacielski, potoczny, emoji
                """.trimMargin() + "\n"
            }
        }
    }

    private fun buildLengthInstruction(context: InspirationContext): String {
        val length = context.requestedLength ?: return ""
        return when (length) {
            "short" -> "=== DŁUGOŚĆ ===\nPost powinien być KRÓTKI: headline + 1-2 zdania opisu + CTA. Max 3-4 linijki.\n"
            "full" -> "=== DŁUGOŚĆ ===\nPost powinien być PEŁNY: headline + 3-5 zdań opisu z detalami + CTA. 6-10 linijek.\n"
            else -> ""
        }
    }

    private fun buildStyleNotesSection(styleNotes: List<String>): String {
        if (styleNotes.isEmpty()) return ""
        val rules = styleNotes.mapIndexed { i, note -> "${i + 1}. $note" }.joinToString("\n")
        return """
            |=== REGUŁY STYLISTYCZNE (NAJWYŻSZY PRIORYTET) ===
            |Poniższe reguły są NADRZĘDNE wobec przykładów POSITIVE.
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
}

class LlmEmptyResponseException(message: String) : RuntimeException(message)
class LlmParsingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
