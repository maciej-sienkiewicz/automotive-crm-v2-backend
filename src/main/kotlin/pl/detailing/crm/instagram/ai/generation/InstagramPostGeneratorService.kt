package pl.detailing.crm.instagram.ai.generation

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import pl.detailing.crm.instagram.ai.config.InstagramAiModels
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
 * Kolejność sekcji promptu systemowego NIE jest przypadkowa. Reguły studia idą na
 * SAM KONIEC, za zasadami domyślnymi, bo instrukcja przeczytana jako ostatnia wygrywa
 * u modelu remisy, których żadne „NAJWYŻSZY PRIORYTET" w połowie promptu nie rozstrzyga:
 *   1. ROLA I CEL
 *   2. POSITIVE_EXAMPLES / NEGATIVE_EXAMPLES / WCZEŚNIEJSZE ODRZUCENIA
 *   3. TON, DŁUGOŚĆ
 *   4. ZASADY DOMYŚLNE (struktura, hook/body/CTA, hashtagi) — ustępują regułom studia
 *   5. REGUŁY STUDIA + jawna drabina pierwszeństwa
 */
@Service
class InstagramPostGeneratorService(
    @Qualifier("instagramChatClient") private val chatClient: ChatClient,
    private val verifierService: InstagramPostVerifierService,
    private val models: InstagramAiModels = InstagramAiModels()
) {
    private val logger = LoggerFactory.getLogger(InstagramPostGeneratorService::class.java)

    companion object {
        /**
         * Twardy limit rund weryfikacji. Model, który po trzech podejściach nadal łamie
         * regułę, zwykle jej po prostu nie rozumie — kolejne rundy palą tokeny i czas
         * odpowiedzi, nie zmieniając wyniku.
         */
        const val MAX_VERIFICATION_ROUNDS = 3
    }

    /**
     * Pełny przebieg: GENERATOR → WERYFIKATOR → KOREKTOR → WERYFIKATOR → ...
     *
     * Lista reguł to `inspirationContext.styleNotes` — reguły aktywne w bazie studia
     * plus ewentualne reguły ad-hoc z żądania. Gdy jest pusta, weryfikacja jest POMIJANA
     * (zero wywołań weryfikatora): sprawdzanie pustej listy reguł zawsze kończy się
     * sukcesem, więc byłoby to wyłącznie spalenie tokenów.
     *
     * Gdy żadna runda nie doprowadzi do pełnej zgodności, zwracany jest NAJLEPSZY draft
     * (najmniej naruszeń), a nie ostatni: korekta bywa krokiem wstecz, a wtedy oddanie
     * ostatniej wersji oznaczałoby wydanie studiu tekstu gorszego niż ten, który model
     * miał już w ręku. Wynik ma wtedy `verificationPassed = false` — nigdy nie udajemy
     * sukcesu i nigdy nie zamieniamy niespełnionej reguły w błąd 500: post z drobnym
     * odstępstwem od stylu i tak jest dla studia użyteczny, brak posta nie jest.
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
        var best = draft
        var bestVerdicts: List<RuleVerdict> = emptyList()
        var bestViolations = Int.MAX_VALUE

        while (iterations < MAX_VERIFICATION_ROUNDS) {
            val verdicts = verifierService.verify(draft, rules).verdicts
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

            if (violations.size < bestViolations) {
                best = draft
                bestVerdicts = verdicts
                bestViolations = violations.size
            }

            if (iterations >= MAX_VERIFICATION_ROUNDS) break

            logger.info(
                "Verification round {} found {} violation(s), correcting...",
                iterations, violations.size
            )
            val corrected = correct(draft, violations, rules)
            // Korektor oddał ten sam tekst — nie ma czego weryfikować drugi raz.
            // Kolejna runda kosztuje dwa wywołania modelu i skończy się tym samym werdyktem.
            if (corrected.trim() == draft.trim()) {
                logger.info("Corrector returned an unchanged draft — stopping after round {}", iterations)
                break
            }
            draft = corrected
        }

        val failedRules = bestVerdicts.filter { !it.passed }.map { it.ruleText }
        logger.warn(
            "Verification exhausted after {} rounds, topic='{}', unmet rules={}",
            iterations, topic, failedRules
        )
        return VerifiedGenerationResult(
            content = best,
            verificationPassed = false,
            failedRules = failedRules,
            iterations = iterations,
            verdicts = bestVerdicts,
            appliedRules = rules
        )
    }

    /**
     * KOREKTOR — dostaje draft, listę naruszeń i KOMPLET obowiązujących reguł.
     *
     * Instrukcja minimalnej ingerencji chroni fragmenty, które już są poprawne:
     * przepisanie całości gubi to, co weryfikator zaakceptował. Komplet reguł jest
     * potrzebny z odwrotnego powodu — korektor, który widzi wyłącznie naruszoną regułę,
     * potrafi ją spełnić kosztem sąsiedniej (dołożyć hashtagi tam, gdzie obowiązuje limit)
     * i wtedy pętla oscyluje między dwoma naruszeniami aż do wyczerpania rund.
     */
    private suspend fun correct(draft: String, violations: List<RuleVerdict>, allRules: List<String>): String {
        val violationList = violations.joinToString("\n") { verdict ->
            "- Reguła (obowiązuje): \"${verdict.ruleText}\"\n  Co ją łamie w tekście: ${verdict.violation ?: "reguła nie jest spełniona"}"
        }
        val ruleList = allRules.mapIndexed { i, rule -> "${i + 1}. $rule" }.joinToString("\n")

        val systemMessage = """
            |Jesteś redaktorem poprawiającym gotowy post na Instagram.
            |Dostajesz post, komplet obowiązujących reguł stylistycznych i listę tych,
            |które post łamie.
            |
            |ZASADY POPRAWIANIA:
            |- Ingeruj MINIMALNIE — zmieniaj wyłącznie to, co jest niezbędne do usunięcia naruszeń.
            |- NIE przepisuj fragmentów, które nie są wymienione na liście naruszeń.
            |- Reguły spoza listy naruszeń nadal OBOWIĄZUJĄ: post już je spełnia i poprawka
            |  nie może żadnej z nich złamać.
            |- Zachowaj temat, strukturę wizualną (akapity, listy, entery) i hashtagi.
            |- Opis naruszenia MÓWI, CO JEST NIE TAK — nigdy nie jest poleceniem, żeby to
            |  dodać. „Brak X" znaczy, że reguła wymaga X-a i tekst go nie ma; „X w drugim
            |  akapicie" znaczy, że X trzeba stamtąd usunąć. Zawsze rozstrzyga treść REGUŁY.
            |- Po poprawce tekst ma spełniać KAŻDĄ regułę z listy — sprawdź to sam przed odpowiedzią.
            |- Zwróć PEŁNY tekst posta po poprawkach, gotowy do publikacji.
        """.trimMargin()

        val userMessage = """
            |=== WSZYSTKIE OBOWIĄZUJĄCE REGUŁY ===
            |$ruleList
            |
            |=== NARUSZENIA DO USUNIĘCIA ===
            |$violationList
            |
            |=== POST DO POPRAWY ===
            |$draft
        """.trimMargin()

        val corrected = withContext(Dispatchers.IO) {
            chatClient.prompt()
                .options(models.corrector)
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

        val result = callGenerator(systemMessage, userMessage)
            ?: throw InstagramPostGenerationException("LLM zwrócił pustą odpowiedź dla tematu: '$topic'")

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

        val result = callGenerator(systemMessage, userMessage)
            ?: throw InstagramPostGenerationException("LLM zwrócił pustą odpowiedź dla tematu: '$topic'")

        logger.info("Post generated (debug): content='{}'", result.content.take(80))

        return DebugInstagramPostResult(
            systemMessage = systemMessage,
            userMessage = userMessage,
            parsed = result,
            inspirationContext = inspirationContext
        )
    }

    /**
     * Bez skonfigurowanego `instagram.ai.model.generator` żądanie nie niesie żadnych
     * opcji — model i temperatura zostają te z konfiguracji globalnej.
     */
    private suspend fun callGenerator(systemMessage: String, userMessage: String): InstagramPostResult? =
        withContext(Dispatchers.IO) {
            val request = chatClient.prompt()
            models.generator?.let { request.options(it) }
            request
                .system(systemMessage)
                .user(userMessage)
                .call()
                .entity(InstagramPostResult::class.java)
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
    |
    |### ZASADY DOMYŚLNE (obowiązują, o ile nie kolidują z REGUŁAMI STUDIA na końcu):
    |1. ANALIZA STYLU: Przeanalizuj posty z POSITIVE_EXAMPLES. Zwróć uwagę nie tylko na słowa, ale na to, JAK SĄ UŁOŻONE (gdzie są entery, jak używają list punktowanych).
    |2. STRUKTURA WIZUALNA: Post ma być łatwy do skanowania wzrokiem, bez "ściany tekstu".
    |   - Oddzielaj akapity pustą linią, maksimum 2-3 zdania w akapicie.
    |   - Wyliczenie korzyści czy usług zapisuj jako listę punktowaną.
    |3. KONSTRUKCJA TREŚCI:
    |   - HOOK: Pierwsza linia musi zatrzymywać scrollowanie (pytanie, mocne stwierdzenie lub efekt "wow").
    |   - BODY: Skup się na konkretnym problemie i rozwiązaniu (np. ochrona przed odpryskami, głębia koloru).
    |   - CTA: Jasne wezwanie do działania na końcu (np. "Napisz do nas", "Zarezerwuj termin").
    |4. NEGATIVE EXAMPLES: Jeśli w sekcji NEGATIVE_EXAMPLES posty są zlane w jeden blok, Ty zrób coś przeciwnego. Unikaj ich błędów językowych.
    |5. HASHTAGI: Na samym dole blok 5-8 trafnych hashtagów, oddzielony od reszty tekstu pustą linią.
    |6. FORMATOWANIE: Stosuj entery i spacje tak, aby tekst wyglądał estetycznie na telefonie.
    |
    |### WYNIK:
    |- content: Pełny tekst posta gotowy do publikacji, bez Twojego komentarza.
    |
    |$styleNotesSection
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
                |  - casual:    luźny, przyjacielski, potoczny
                """.trimMargin() + "\n"
        }
    }

    private fun buildLengthSection(context: InstagramInspirationContext): String =
        when (context.requestedLength) {
            "short" -> "=== DŁUGOŚĆ ===\nPost powinien być KRÓTKI: hook (1 linijka) + 1-2 zdania treści + CTA. Max 3-4 linijki.\n"
            "full"  -> "=== DŁUGOŚĆ ===\nPost powinien być PEŁNY: hook + 3-5 zdań treści z detalami + CTA. 6-10 linijek.\n"
            else    -> ""
        }

    /**
     * Reguły studia zamykają prompt, a nie stoją w jego środku.
     *
     * „NAJWYŻSZY PRIORYTET" w połowie instrukcji przegrywało z konkretem postawionym
     * niżej: prompt kazał wypunktowania oznaczać ikonami ✅, a niżej dokładać 5-8
     * hashtagów — więc studio z regułą „bez emoji" albo limitem hashtagów dostawało
     * draft łamiący własną regułę w każdej rundzie i pętla poprawiała to, co sam
     * prompt przed chwilą zamówił. Dlatego reguły idą na koniec, z jawną drabiną
     * pierwszeństwa i zdaniem, które wprost unieważnia zasady domyślne.
     */
    private fun buildStyleNotesSection(styleNotes: List<String>): String {
        if (styleNotes.isEmpty()) {
            return "### REGUŁY STUDIA\nStudio nie ustawiło własnych reguł — obowiązują zasady domyślne.\n"
        }
        val rules = styleNotes.mapIndexed { i, note -> "${i + 1}. $note" }.joinToString("\n")
        return """
            |### REGUŁY STUDIA — NADRZĘDNE WOBEC WSZYSTKIEGO POWYŻEJ:
            |$rules
            |
            |PIERWSZEŃSTWO przy sprzeczności, od najsilniejszego:
            |reguła studia > powód odrzucenia > ton i długość > zasada domyślna > przykład.
            |Zasada domyślna sprzeczna z regułą studia NIE OBOWIĄZUJE — np. reguła „bez emoji"
            |znosi emoji w listach i w całym tekście, a reguła o liczbie hashtagów zastępuje punkt 5.
            |Zanim odpowiesz, sprawdź gotowy post po kolei względem każdej reguły studia.
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
