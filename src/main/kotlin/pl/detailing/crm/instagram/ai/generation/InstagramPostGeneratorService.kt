package pl.detailing.crm.instagram.ai.generation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import pl.detailing.crm.instagram.ai.model.DebugInstagramPostResult
import pl.detailing.crm.instagram.ai.model.InstagramInspirationContext
import pl.detailing.crm.instagram.ai.model.InstagramPostResult
import pl.detailing.crm.instagram.ai.model.RuleVerdict
import pl.detailing.crm.instagram.ai.model.VerifiedGenerationResult
import pl.detailing.crm.instagram.ai.verification.InstagramPostVerifierService

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
    @Qualifier("instagramChatClient") private val chatClient: ChatClient,
    private val verifierService: InstagramPostVerifierService
) {
    private val logger = LoggerFactory.getLogger(InstagramPostGeneratorService::class.java)

    companion object {
        /**
         * Twardy limit rund weryfikacji. Model, który po trzech podejściach nadal łamie
         * regułę, zwykle jej po prostu nie rozumie — kolejne rundy palą tokeny i czas
         * odpowiedzi, nie zmieniając wyniku.
         */
        const val MAX_VERIFICATION_ROUNDS = 3

        /**
         * Korekta ma poprawić naruszenie, a nie napisać post od nowa — stąd temperatura
         * niższa od generatora (0.7), ale nie zerowa: przepisanie fragmentu wymaga
         * odrobiny swobody językowej.
         */
        private const val CORRECTOR_TEMPERATURE = 0.3
    }

    /**
     * Pełny przebieg: GENERATOR → WERYFIKATOR → KOREKTOR → WERYFIKATOR → ...
     *
     * Lista reguł to `inspirationContext.styleNotes` — reguły aktywne w bazie studia
     * plus ewentualne reguły ad-hoc z żądania. Gdy jest pusta, weryfikacja jest POMIJANA
     * (zero wywołań weryfikatora): sprawdzanie pustej listy reguł zawsze kończy się
     * sukcesem, więc byłoby to wyłącznie spalenie tokenów.
     *
     * Po wyczerpaniu [MAX_VERIFICATION_ROUNDS] zwracana jest najlepsza uzyskana wersja
     * z `verificationPassed = false` — nigdy nie udajemy sukcesu i nigdy nie zamieniamy
     * niespełnionej reguły w błąd 500: post z drobnym odstępstwem od stylu i tak jest
     * dla studia użyteczny, brak posta nie jest.
     */
    suspend fun generateVerified(
        topic: String,
        additionalContext: String?,
        inspirationContext: InstagramInspirationContext
    ): VerifiedGenerationResult {
        val rules = inspirationContext.styleNotes
        var draft = generate(topic, additionalContext, inspirationContext).content

        if (rules.isEmpty()) {
            logger.info("No style rules for topic='{}' — skipping verification", topic)
            return VerifiedGenerationResult(
                content = draft,
                verificationPassed = true,
                failedRules = emptyList(),
                iterations = 0,
                verdicts = emptyList(),
                appliedRules = rules
            )
        }

        var iterations = 0
        var verdicts: List<RuleVerdict> = emptyList()

        while (iterations < MAX_VERIFICATION_ROUNDS) {
            verdicts = verifierService.verify(draft, rules).verdicts
            iterations++

            val violations = verdicts.filter { !it.passed }
            if (violations.isEmpty()) {
                logger.info("Verification passed after {} round(s), topic='{}'", iterations, topic)
                return VerifiedGenerationResult(
                    content = draft,
                    verificationPassed = true,
                    failedRules = emptyList(),
                    iterations = iterations,
                    verdicts = verdicts,
                    appliedRules = rules
                )
            }

            if (iterations >= MAX_VERIFICATION_ROUNDS) break

            logger.info(
                "Verification round {} found {} violation(s), correcting...",
                iterations, violations.size
            )
            draft = correct(draft, violations)
        }

        val failedRules = verdicts.filter { !it.passed }.map { it.ruleText }
        logger.warn(
            "Verification exhausted after {} rounds, topic='{}', unmet rules={}",
            iterations, topic, failedRules
        )
        return VerifiedGenerationResult(
            content = draft,
            verificationPassed = false,
            failedRules = failedRules,
            iterations = iterations,
            verdicts = verdicts,
            appliedRules = rules
        )
    }

    /**
     * KOREKTOR — dostaje draft i WYŁĄCZNIE listę naruszeń (bez promptu generatora
     * i bez reguł, które przeszły). Instrukcja minimalnej ingerencji chroni fragmenty,
     * które już są poprawne: przepisanie całości gubi to, co weryfikator zaakceptował,
     * i zwykle wprowadza nowe naruszenia.
     */
    private suspend fun correct(draft: String, violations: List<RuleVerdict>): String {
        val violationList = violations.joinToString("\n") { verdict ->
            "- Reguła: \"${verdict.ruleText}\" — naruszenie: ${verdict.violation ?: "reguła nie jest spełniona"}"
        }

        val systemMessage = """
            |Jesteś redaktorem poprawiającym gotowy post na Instagram.
            |Dostajesz post oraz listę naruszonych reguł stylistycznych.
            |
            |ZASADY POPRAWIANIA:
            |- Ingeruj MINIMALNIE — zmieniaj wyłącznie to, co jest niezbędne do usunięcia naruszeń.
            |- NIE przepisuj fragmentów, które nie są wymienione na liście naruszeń.
            |- Zachowaj temat, strukturę wizualną (akapity, listy, entery) i hashtagi.
            |- Zwróć PEŁNY tekst posta po poprawkach, gotowy do publikacji.
        """.trimMargin()

        val userMessage = """
            |=== NARUSZENIA DO USUNIĘCIA ===
            |$violationList
            |
            |=== POST DO POPRAWY ===
            |$draft
        """.trimMargin()

        val corrected = withContext(Dispatchers.IO) {
            chatClient.prompt()
                .options(OpenAiChatOptions.builder().temperature(CORRECTOR_TEMPERATURE).build())
                .system(systemMessage)
                .user(userMessage)
                .call()
                .entity(InstagramPostResult::class.java)
        } ?: throw InstagramPostGenerationException("LLM zwrócił pustą odpowiedź przy korekcie posta")

        return corrected.content
    }

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

        val ownRejectionsSection = buildOwnRejectionsSection(context)
        val toneSection = buildToneSection(context)
        val lengthSection = buildLengthSection(context)
        val styleNotesSection = buildStyleNotesSection(context.styleNotes)

        return """
    |Jesteś profesjonalnym Copywriterem specjalizującym się w branży Automotive i Detailing.
    |Twoim zadaniem jest stworzenie angażującego posta na Instagram, który sprzedaje usługę poprzez korzyści i profesjonalizm.
    |
    |$positiveSection
    |$negativeSection
    |$ownRejectionsSection
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

    /**
     * Sekcja WCZEŚNIEJSZE ODRZUCENIA — własne posty studia ocenione negatywnie
     * wraz z komentarzem „dlaczego".
     *
     * Osobna od NEGATIVE_EXAMPLES celowo: komentarz „za dużo wykrzykników" to konkretna
     * instrukcja od tego studia, a wrzucony do wspólnego worka anonimowych negatywów
     * traci całą swoją wartość.
     */
    private fun buildOwnRejectionsSection(context: InstagramInspirationContext): String {
        if (context.ownRejections.isEmpty()) return ""
        val entries = context.ownRejections.joinToString("\n\n") { rejection ->
            val reason = rejection.comment?.takeIf { it.isNotBlank() }
                ?: "Studio odrzuciło ten post bez komentarza."
            "POST: \"${rejection.content}\"\nPOWÓD ODRZUCENIA: $reason"
        }
        return """
            |=== WCZEŚNIEJSZE ODRZUCENIA (Twoje wcześniejsze propozycje, które studio ODRZUCIŁO) ===
            |To są posty wygenerowane dla TEGO studia i przez nie odrzucone, wraz z powodem.
            |Powód odrzucenia traktuj jak twardą regułę — NIE POWTARZAJ tych błędów.
            |
            |$entries
        """.trimMargin() + "\n"
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
