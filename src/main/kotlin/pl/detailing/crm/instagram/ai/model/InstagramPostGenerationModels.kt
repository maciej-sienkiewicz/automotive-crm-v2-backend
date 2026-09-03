package pl.detailing.crm.instagram.ai.model

// ── Żądania ─────────────────────────────────────────────────────────────────

/**
 * Żądanie generowania posta Instagram przez LLM.
 *
 * @param topic          Temat posta (np. "Nowe oklejanie PPF na BMW M4")
 * @param context        Dodatkowy kontekst (np. opis realizacji, specyfika klienta)
 * @param postTone       Preferowany ton: premium | technical | emotional | casual
 * @param postLength     Preferowana długość: short | full
 * @param styleNotes     Reguły stylistyczne nadrzędne wobec przykładów few-shot,
 *                       np. ["Nie używaj emoji", "Pisz po angielsku"]
 */
data class GenerateInstagramPostRequest(
    val topic: String,
    val context: String? = null,
    val postTone: String? = null,
    val postLength: String? = null,
    val styleNotes: List<String>? = null
)

/**
 * Żądanie testu A/B negatywnych promptów.
 */
data class InstagramAbTestRequest(
    val topic: String,
    val context: String? = null
)

// ── Odpowiedzi generowania ───────────────────────────────────────────────────

/**
 * Wynik generowania posta Instagram przez LLM.
 * Pojedyncze pole [content] zawiera gotowy tekst posta — ready to copy-paste na Instagram.
 */
data class InstagramPostResult(
    val content: String
)

/**
 * Odpowiedź debug: pełny prompt + wynik LLM + kontekst inspiracji.
 */
data class DebugInstagramPostResult(
    val systemMessage: String,
    val userMessage: String,
    val parsed: InstagramPostResult,
    val inspirationContext: InstagramInspirationContext
)

/**
 * Wynik testu A/B: porównanie wariantu z negatywami vs bez.
 */
data class InstagramAbTestResult(
    val topic: String,
    val positiveExamplesUsed: List<String>,
    val negativeExamplesUsed: List<String>,
    val variantA: InstagramAbTestVariant,
    val variantB: InstagramAbTestVariant,
    val verdict: String
)

data class InstagramAbTestVariant(
    val label: String,
    val result: InstagramPostResult
)

/**
 * Wynik 3-wariantowego testu wpływu negatywnych przykładów.
 */
data class InstagramNegativeImpactTestResult(
    val topic: String,
    val analysis: NegativeImpactAnalysis,
    val variantA: NegativeImpactVariant,
    val variantB: NegativeImpactVariant,
    val variantC: NegativeImpactVariant,
    val conclusion: String
)

data class NegativeImpactVariant(
    val label: String,
    val positiveExamples: List<String>,
    val negativeExamples: List<String>,
    val result: InstagramPostResult,
    val metrics: StyleMetrics
)

data class NegativeImpactAnalysis(
    val description: String,
    val expectedBehavior: String
)

/**
 * Metryki stylistyczne do automatycznej oceny wygenerowanego tekstu.
 */
data class StyleMetrics(
    val exclamationMarks: Int,
    val upperCaseRatio: Double,
    val wordCount: Int,
    val hasAllCapsWords: Boolean,
    val aggressiveKeywords: List<String>
)

// ── Klasyfikacja posta ───────────────────────────────────────────────────────

/**
 * Wynik klasyfikacji posta Instagramowego przez LLM.
 * Używany przy indeksowaniu reakcji do bazy wektorowej.
 */
data class InstagramPostClassification(
    val postTone: String,       // premium | technical | emotional | casual
    val serviceType: String,    // ppf | ceramic | detailing | interior | wrap | polish | other
    val carBrand: String,       // konkretna marka lub "universal"
    val embeddingText: String   // skondensowany opis semantyczny do embeddingu
)

// ── Kontekst inspiracji (Retrieval) ─────────────────────────────────────────

/**
 * Kontekst inspiracji zebrany z VectorStore (bazy wektorowej pgvector).
 * Zasilanie prompta few-shot na podstawie polubień/odrzuceń studia.
 */
data class InstagramInspirationContext(
    val positiveExamples: List<String>,
    val negativeExamples: List<String>,
    val requestedTone: String?,
    val requestedLength: String?,
    val fallbackInfo: FallbackInfo,
    /** Reguły stylistyczne — nadrzędne wobec przykładów few-shot */
    val styleNotes: List<String> = emptyList(),
    /** Własne posty studia odrzucone przy ocenie — razem z komentarzem „dlaczego" */
    val ownRejections: List<OwnRejectionExample> = emptyList()
)

/**
 * Informacja o poziomie fallbacku w strategii warstwowego wyszukiwania.
 *
 * Level 1 – ideał: LIKED + studio + ton + długość
 * Level 2 – globalny ton: LIKED + ton + długość (bez filtra studia)
 * Level 3 – tylko studio: LIKED + studio (bez ton/długość)
 * Level 4 – brak przykładów (LLM generuje bez few-shot)
 */
data class FallbackInfo(
    val level: Int,
    val description: String,
    val suggestion: String?
) {
    companion object {
        fun ideal() = FallbackInfo(
            level = 1,
            description = "Znaleziono posty pasujące do tonu, długości i studia.",
            suggestion = null
        )

        fun globalTone() = FallbackInfo(
            level = 2,
            description = "Brak postów studia w żądanym tonie/długości. Użyto globalnych przykładów w tym tonie.",
            suggestion = "Polub więcej postów konkurencji w żądanym tonie, żeby system lepiej się dopasował."
        )

        fun studioOnly() = FallbackInfo(
            level = 3,
            description = "Brak postów w żądanym tonie (globalnie). Użyto ogólnych preferencji studia.",
            suggestion = "Polub więcej postów w żądanym tonie, żeby system nauczył się go rozróżniać."
        )

        fun empty() = FallbackInfo(
            level = 4,
            description = "Brak jakichkolwiek polubionych postów. LLM generuje bez przykładów few-shot.",
            suggestion = "Zacznij oceniać posty konkurencji (LIKED/DISLIKED), żeby system nauczył się stylu Twojego studia."
        )
    }
}

// ── Odpowiedź błędu ──────────────────────────────────────────────────────────

data class InstagramAiErrorResponse(
    val error: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

// ── Weryfikacja reguł (pętla generuj → weryfikuj → popraw) ───────────────────

/**
 * Werdykt weryfikatora dla POJEDYNCZEJ reguły stylistycznej.
 *
 * @param ruleIndex Numer reguły na liście przekazanej weryfikatorowi (od 1). Nie jest
 *                  nośnikiem prawdy — modele bywają w tym niekonsekwentne, więc
 *                  przypisanie werdyktu do reguły idzie przede wszystkim po [ruleText].
 * @param ruleText  Treść reguły przepisana przez model — po niej wiążemy werdykt z regułą
 * @param passed    Czy draft spełnia regułę. Domyślnie TRUE: brak pola w odpowiedzi modelu
 *                  ma znaczyć „nie stwierdzono naruszenia", a nie zmyślone naruszenie,
 *                  które uruchamia korektę poprawnego tekstu.
 * @param violation Cytat z posta uzasadniający naruszenie (wymagany, gdy [passed] = false)
 */
data class RuleVerdict(
    val ruleIndex: Int = 0,
    val ruleText: String = "",
    val passed: Boolean = true,
    val violation: String? = null
)

/**
 * Odpowiedź weryfikatora — werdykt dla każdej z przekazanych reguł.
 * Structured output LLM (temperatura 0.0).
 */
data class VerificationReport(
    val verdicts: List<RuleVerdict> = emptyList()
)

/**
 * Raport pętli zapisywany w kolumnie `verification_report` (JSONB).
 * Trzyma ostatnie werdykty ORAZ liczbę wykonanych rund — po fakcie widać,
 * czy post przeszedł od razu, czy dopiero po korektach (albo wcale).
 */
data class StoredVerificationReport(
    val iterations: Int,
    val passed: Boolean,
    val verdicts: List<RuleVerdict>
)

/**
 * Wynik pełnego przebiegu generowania z weryfikacją.
 *
 * @param iterations Liczba WYKONANYCH rund weryfikacji (0 = brak reguł, weryfikacja pominięta)
 * @param failedRules Treści reguł niespełnionych w ostatniej rundzie (puste, gdy [verificationPassed])
 */
data class VerifiedGenerationResult(
    val content: String,
    val verificationPassed: Boolean,
    val failedRules: List<String>,
    val iterations: Int,
    val verdicts: List<RuleVerdict>,
    val appliedRules: List<String>
)

// ── Odpowiedź /generate ──────────────────────────────────────────────────────

/**
 * Niespełniona reguła razem z UZASADNIENIEM od weryfikatora.
 *
 * Sama nazwa reguły nie wystarcza: „post łamie regułę «bez emoji»" przy poście bez
 * emoji nie daje się ani zweryfikować, ani zgłosić. Cytat z tekstu zamienia werdykt
 * w coś, co użytkownik może sprawdzić wzrokiem w sekundę.
 */
data class FailedRule(
    val rule: String,
    val reason: String
)

/**
 * Odpowiedź endpointu POST /generate.
 *
 * Pole [content] zachowane w niezmienionej formie — frontend już z niego korzysta;
 * pozostałe pola są DODANE, nie zamieniają niczego.
 */
data class GenerateInstagramPostResponse(
    val content: String,
    val postId: String,
    val verificationPassed: Boolean,
    val failedRules: List<String>,
    /** To samo co [failedRules], ale z powodem — po jednym wpisie na regułę. */
    val failedRuleDetails: List<FailedRule>,
    val iterations: Int
)

// ── Reguły stylistyczne (CRUD) ───────────────────────────────────────────────

data class CreateStyleRuleRequest(
    val ruleText: String
)

data class UpdateStyleRuleRequest(
    val ruleText: String? = null,
    val active: Boolean? = null
)

data class StyleRuleResponse(
    val id: String,
    val ruleText: String,
    val active: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

// ── Ocena wygenerowanych postów ──────────────────────────────────────────────

enum class GeneratedPostRating { POSITIVE, NEGATIVE }

data class RateGeneratedPostRequest(
    val rating: GeneratedPostRating,
    val comment: String? = null
)

/**
 * Pozycja historii wygenerowanych postów (GET /posts).
 */
data class GeneratedPostResponse(
    val id: String,
    val topic: String,
    val content: String,
    val requestedTone: String?,
    val requestedLength: String?,
    val rating: String?,
    val ratingComment: String?,
    val verificationPassed: Boolean?,
    val iterations: Int?,
    val failedRules: List<String>,
    val rulesSnapshot: List<String>,
    val createdAt: Long,
    val ratedAt: Long?
)

// ── Własne odrzucone posty (pętla uczenia) ───────────────────────────────────

/**
 * Własny post studia oceniony negatywnie wraz z komentarzem „dlaczego".
 *
 * Trzymany osobno od anonimowych negatywnych przykładów konkurencji: komentarz
 * „za dużo wykrzykników" niesie konkretną instrukcję, która rozmyłaby się,
 * gdyby post wpadł do wspólnego worka NEGATIVE_EXAMPLES.
 */
data class OwnRejectionExample(
    val content: String,
    val comment: String?
)
