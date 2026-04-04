package com.example.demo.adcopy.model

/**
 * Wynik generowania posta Instagram przez LLM.
 */
data class AdCopyResponse(
    val headline: String,
    val description: String,
    val punchline: String
)

/**
 * Żądanie wejściowe z endpointu POST /api/ads/generate.
 */
data class AdGenerateRequest(
    val topic: String,
    val context: String? = null,
    val userId: Long,
    val postTone: String? = null,
    val postLength: String? = null,
    val serviceType: String? = null,
    /** Reguły stylistyczne nadrzędne wobec przykładów few-shot, np.
     *  ["Nie używaj emoji", "Unikaj wykrzykników", "Pisz po angielsku"] */
    val styleNotes: List<String>? = null
)

/**
 * Kontekst inspiracji zebrany z VectorStore – pozytywne i negatywne przykłady
 * z informacją o tym, z jakiego poziomu fallbacku pochodzą.
 */
data class InspirationContext(
    val positiveExamples: List<String>,
    val negativeExamples: List<String>,
    val requestedTone: String?,
    val requestedLength: String?,
    val fallbackInfo: FallbackInfo,
    /** Reguły stylistyczne — nadrzędne wobec przykładów few-shot.
     *  Nawet jeśli przykłady zawierają emoji, reguła "Nie używaj emoji" je zablokuje. */
    val styleNotes: List<String> = emptyList()
)

data class FallbackInfo(
    val level: Int,
    val description: String,
    val suggestion: String?
) {
    companion object {
        fun ideal() = FallbackInfo(1, "Znaleziono posty pasujące do tonu, długości i użytkownika.", null)
        fun relaxService() = FallbackInfo(2, "Znaleziono posty pasujące do tonu i długości użytkownika, ale bez filtra usługi.", null)
        fun globalTone() = FallbackInfo(3, "Brak postów użytkownika w żądanym tonie/długości. Użyto globalnych przykładów w tym tonie.", "Polub kilka postów w żądanym tonie, żeby system lepiej się dopasował.")
        fun userOnly() = FallbackInfo(4, "Brak postów w żądanym tonie (również globalnie). Użyto ogólnych preferencji użytkownika.", "Polub kilka postów w żądanym tonie, żeby system lepiej się dopasował.")
        fun empty() = FallbackInfo(5, "Brak jakichkolwiek polubiony postów. LLM generuje bez przykładów few-shot.", "Polub kilka postów, żeby system nauczył się Twojego stylu.")
    }
}

/**
 * Standardowa odpowiedź błędu API.
 */
data class ErrorResponse(
    val error: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Odpowiedź debug: pełny prompt + wynik LLM.
 */
data class DebugAdCopyResponse(
    val systemMessage: String,
    val userMessage: String,
    val rawLlmResponse: String,
    val parsed: AdCopyResponse,
    val inspirationContext: InspirationContext
)

/**
 * Żądanie A/B testu negatywnych promptów.
 */
data class AbTestRequest(
    val topic: String,
    val context: String? = null,
    val userId: Long
)

/**
 * Wynik testu A/B: porównanie wariantu z negatywami vs bez.
 */
data class AbTestResponse(
    val topic: String,
    val positiveExamplesUsed: List<String>,
    val negativeExamplesUsed: List<String>,
    val variantA: AbTestVariant,
    val variantB: AbTestVariant,
    val verdict: String
)

data class AbTestVariant(
    val label: String,
    val systemMessageExcerpt: String,
    val result: AdCopyResponse
)

/**
 * Wynik 3-wariantowego testu wpływu negatywnych promptów.
 */
data class NegativeImpactTestResponse(
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
    val result: AdCopyResponse,
    val metrics: StyleMetrics
)

data class StyleMetrics(
    val exclamationMarks: Int,
    val upperCaseRatio: Double,
    val wordCount: Int,
    val hasAllCapsWords: Boolean,
    val aggressiveKeywords: List<String>
)

data class NegativeImpactAnalysis(
    val description: String,
    val expectedBehavior: String
)

// ── Post Feedback ───────────────────────────────────────────────

data class PostFeedbackRequest(
    val feedbackStatus: String,  // LIKE / DISLIKE
    val userId: Long
)

/**
 * Wynik klasyfikacji posta przez LLM.
 * BeanOutputConverter parsuje odpowiedź LLM do tego obiektu.
 */
data class PostClassification(
    val postTone: String,
    val serviceType: String,
    val carBrand: String,
    val embeddingText: String
)

data class PostFeedbackResponse(
    val postId: Long,
    val feedbackStatus: String,
    val classification: PostClassification,
    val postLength: String,
    val indexed: Boolean
)
