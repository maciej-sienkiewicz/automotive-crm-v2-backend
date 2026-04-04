package pl.detailing.crm.instagram.ai.model

// ── Żądania ─────────────────────────────────────────────────────────────────

/**
 * Żądanie generowania posta Instagram przez LLM.
 *
 * @param topic          Temat posta (np. "Nowe oklejanie PPF na BMW M4")
 * @param context        Dodatkowy kontekst (np. opis realizacji, specyfika klienta)
 * @param postTone       Preferowany ton: premium | technical | emotional | casual
 * @param postLength     Preferowana długość: short | full
 * @param serviceType    Rodzaj usługi: ppf | ceramic | detailing | interior | wrap | polish | other
 * @param styleNotes     Reguły stylistyczne nadrzędne wobec przykładów few-shot,
 *                       np. ["Nie używaj emoji", "Pisz po angielsku"]
 */
data class GenerateInstagramPostRequest(
    val topic: String,
    val context: String? = null,
    val postTone: String? = null,
    val postLength: String? = null,
    val serviceType: String? = null,
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
    val styleNotes: List<String> = emptyList()
)

/**
 * Informacja o poziomie fallbacku w strategii warstwowego wyszukiwania.
 *
 * Level 1 – ideał: LIKED + studio + ton + długość + usługa
 * Level 2 – relaks usługi: LIKED + studio + ton + długość
 * Level 3 – globalny ton: LIKED + ton + długość (bez filtra studia)
 * Level 4 – tylko studio: LIKED + studio (bez ton/długość)
 * Level 5 – brak przykładów (LLM generuje bez few-shot)
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

        fun relaxService() = FallbackInfo(
            level = 2,
            description = "Znaleziono posty pasujące do tonu i długości studia (bez filtra usługi).",
            suggestion = null
        )

        fun globalTone() = FallbackInfo(
            level = 3,
            description = "Brak postów studia w żądanym tonie/długości. Użyto globalnych przykładów w tym tonie.",
            suggestion = "Polub więcej postów konkurencji w żądanym tonie, żeby system lepiej się dopasował."
        )

        fun studioOnly() = FallbackInfo(
            level = 4,
            description = "Brak postów w żądanym tonie (globalnie). Użyto ogólnych preferencji studia.",
            suggestion = "Polub więcej postów w żądanym tonie, żeby system nauczył się go rozróżniać."
        )

        fun empty() = FallbackInfo(
            level = 5,
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
