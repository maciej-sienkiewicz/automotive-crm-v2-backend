package pl.detailing.crm.instagram.ai

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import pl.detailing.crm.auth.SecurityContextHelper
import pl.detailing.crm.instagram.ai.classification.InstagramPostClassificationException
import pl.detailing.crm.instagram.ai.generation.InstagramPostGenerationException
import pl.detailing.crm.instagram.ai.generation.InstagramPostGeneratorService
import pl.detailing.crm.instagram.ai.inspiration.InstagramInspirationService
import pl.detailing.crm.instagram.ai.model.*

/**
 * Kontroler REST dla modułu generowania postów Instagram za pomocą AI.
 *
 * Wszystkie endpointy wymagają uwierzytelnienia – studioId jest pobierane z kontekstu
 * bezpieczeństwa Spring Security, co zapewnia izolację per-tenant.
 *
 * Ścieżka bazowa: /api/v1/instagram/ai
 */
@RestController
@RequestMapping("/api/v1/instagram/ai")
class InstagramPostGenerationController(
    private val inspirationService: InstagramInspirationService,
    private val generatorService: InstagramPostGeneratorService
) {
    private val logger = LoggerFactory.getLogger(InstagramPostGenerationController::class.java)

    // ── Generowanie posta ──────────────────────────────────────────────────────

    /**
     * POST /api/v1/instagram/ai/generate
     *
     * Generuje nowy post Instagram dla zalogowanego studia.
     *
     * Przepływ:
     *   1. Pobiera studioId z kontekstu bezpieczeństwa (multi-tenant)
     *   2. Retrieval: wyszukuje polubione/odrzucone posty w VectorStore (few-shot)
     *   3. Generation: buduje prompt i wywołuje OpenAI
     *
     * Zwraca: [InstagramPostResult] z headline, description, punchline
     */
    @PostMapping("/generate")
    fun generatePost(
        @RequestBody request: GenerateInstagramPostRequest
    ): ResponseEntity<InstagramPostResult> = runBlocking {
        require(request.topic.isNotBlank()) { "Temat posta nie może być pusty" }

        val principal = SecurityContextHelper.getCurrentUser()
        logger.info(
            "Generate request: studioId={}, topic='{}', tone={}, length={}, service={}",
            principal.studioId, request.topic, request.postTone, request.postLength, request.serviceType
        )

        val inspirationContext = inspirationService.getInspirationContext(
            topic = request.topic,
            studioId = principal.studioId,
            postTone = request.postTone,
            postLength = request.postLength,
            serviceType = request.serviceType,
            styleNotes = request.styleNotes ?: emptyList()
        )

        val result = generatorService.generate(
            topic = request.topic,
            additionalContext = request.context,
            inspirationContext = inspirationContext
        )

        logger.info("Post generated: studioId={}, headline='{}'", principal.studioId, result.headline)
        ResponseEntity.ok(result)
    }

    // ── Debug ──────────────────────────────────────────────────────────────────

    /**
     * POST /api/v1/instagram/ai/debug-generate
     *
     * Jak /generate, ale zwraca pełen prompt (system + user message),
     * kontekst inspiracji i wynik — do weryfikacji zachowania modelu.
     */
    @PostMapping("/debug-generate")
    fun debugGenerate(
        @RequestBody request: GenerateInstagramPostRequest
    ): ResponseEntity<DebugInstagramPostResult> = runBlocking {
        require(request.topic.isNotBlank()) { "Temat posta nie może być pusty" }

        val principal = SecurityContextHelper.getCurrentUser()

        val inspirationContext = inspirationService.getInspirationContext(
            topic = request.topic,
            studioId = principal.studioId,
            postTone = request.postTone,
            postLength = request.postLength,
            serviceType = request.serviceType,
            styleNotes = request.styleNotes ?: emptyList()
        )

        val debugResult = generatorService.generateWithDebug(
            topic = request.topic,
            additionalContext = request.context,
            inspirationContext = inspirationContext
        )

        ResponseEntity.ok(debugResult)
    }

    // ── Test A/B negatywnych przykładów ────────────────────────────────────────

    /**
     * POST /api/v1/instagram/ai/ab-test
     *
     * Test A/B — generuje DWA warianty dla tego samego tematu:
     *   Wariant A: pełny przepływ (z negatywnymi przykładami)
     *   Wariant B: bez negatywnych przykładów (baseline)
     *
     * Pozwala ocenić, czy reakcje DISLIKED faktycznie wpływają na styl generowanego posta.
     */
    @PostMapping("/ab-test")
    fun abTest(
        @RequestBody request: InstagramAbTestRequest
    ): ResponseEntity<InstagramAbTestResult> = runBlocking {
        require(request.topic.isNotBlank()) { "Temat posta nie może być pusty" }

        val principal = SecurityContextHelper.getCurrentUser()
        logger.info("A/B test: studioId={}, topic='{}'", principal.studioId, request.topic)

        val fullContext = inspirationService.getInspirationContext(
            topic = request.topic,
            studioId = principal.studioId
        )

        // Wariant A: pełny kontekst (z negatywami)
        logger.debug("Generating A/B Variant A (WITH negatives)...")
        val variantADebug = generatorService.generateWithDebug(
            topic = request.topic,
            additionalContext = request.context,
            inspirationContext = fullContext
        )

        // Wariant B: bez negatywnych przykładów
        val contextWithoutNegatives = fullContext.copy(negativeExamples = emptyList())
        logger.debug("Generating A/B Variant B (WITHOUT negatives)...")
        val variantBDebug = generatorService.generateWithDebug(
            topic = request.topic,
            additionalContext = request.context,
            inspirationContext = contextWithoutNegatives
        )

        val result = InstagramAbTestResult(
            topic = request.topic,
            positiveExamplesUsed = fullContext.positiveExamples,
            negativeExamplesUsed = fullContext.negativeExamples,
            variantA = InstagramAbTestVariant(
                label = "Z NEGATYWNYMI przykładami (${fullContext.negativeExamples.size} szt.)",
                result = variantADebug.parsed
            ),
            variantB = InstagramAbTestVariant(
                label = "BEZ negatywnych przykładów (baseline)",
                result = variantBDebug.parsed
            ),
            verdict = "Wariant A powinien UNIKAĆ stylu z sekcji NEGATIVE_EXAMPLES " +
                "(np. wykrzykniki, ALL CAPS, agresywne CTA). " +
                "Wariant B nie ma takich ograniczeń. " +
                "Porównaj content obu wariantów — różnice wskazują skuteczność negatywnych przykładów."
        )

        logger.info(
            "A/B test done: A='{}'  B='{}'",
            variantADebug.parsed.headline, variantBDebug.parsed.headline
        )
        ResponseEntity.ok(result)
    }

    // ── Test wpływu negatywnych przykładów ─────────────────────────────────────

    /**
     * POST /api/v1/instagram/ai/negative-impact-test
     *
     * Kontrolowany 3-wariantowy test z hardcodowanymi przykładami (niezależny od VectorStore).
     *   Wariant A: eleganckie POSITIVE + agresywne NEGATIVE → powinien być elegancki
     *   Wariant B: eleganckie POSITIVE, brak NEGATIVE       → baseline
     *   Wariant C: agresywne POSITIVE, brak NEGATIVE        → powinien być agresywny
     *
     * Automatycznie mierzy metryki stylistyczne (wykrzykniki, ALL CAPS, agresywne słowa).
     */
    @PostMapping("/negative-impact-test")
    fun negativeImpactTest(): ResponseEntity<InstagramNegativeImpactTestResult> = runBlocking {
        logger.info("Starting negative impact test with hardcoded car detailing examples...")

        val topic = "Nowe zabezpieczenie lakieru — dlaczego warto"

        val elegantExamples = listOf(
            "Mercedes-AMG GT w pełnej ochronie PPF💎\nLakier zabezpieczony. Jazda bez trosk.\n📍Studio",
            "Porsche 911 GT3 — precyzja ochrony.\nKażdy detal ma znaczenie.\nFolia PPF od przedniego zderzaka po tylny spoiler.\n📍Studio",
            "Powłoka ceramiczna — hydrofobowość na lata.\nEfekt, który widać przy każdym myciu💧\n📍Studio",
            "Weekend. Droga. Twój samochód. Wolność.🌅\nPowłoka ceramiczna — żeby ten moment trwał wiecznie.",
            "Ten lakier przeszedł więcej niż myślisz...\nFolia PPF pisze nowy rozdział tej historii.🖤"
        )

        val aggressiveExamples = listOf(
            "MEGA PROMOCJA!!! FOLIA PPF ZA PÓŁ CENY!!! KUP TERAZ!!!",
            "SZOK!!! NAJLEPSZA CERAMIKA NA RYNKU!!! MUSISZ TO MIEĆ!!!",
            "NIE PRZEGAP!!! OSTATNIE MIEJSCA NA DETAILING!!! ZAPISZ SIĘ TERAZ!!!"
        )

        val idealFallback = FallbackInfo.ideal()

        // Wariant A: eleganckie POSITIVE + agresywne NEGATIVE
        val contextA = InstagramInspirationContext(
            positiveExamples = elegantExamples,
            negativeExamples = aggressiveExamples,
            requestedTone = null,
            requestedLength = null,
            fallbackInfo = idealFallback
        )
        val resultA = generatorService.generateWithDebug(topic, null, contextA)

        // Wariant B: eleganckie POSITIVE, brak NEGATIVE (baseline)
        val contextB = InstagramInspirationContext(
            positiveExamples = elegantExamples,
            negativeExamples = emptyList(),
            requestedTone = null,
            requestedLength = null,
            fallbackInfo = idealFallback
        )
        val resultB = generatorService.generateWithDebug(topic, null, contextB)

        // Wariant C: agresywne POSITIVE, brak NEGATIVE (kontrast)
        val contextC = InstagramInspirationContext(
            positiveExamples = aggressiveExamples,
            negativeExamples = emptyList(),
            requestedTone = null,
            requestedLength = null,
            fallbackInfo = idealFallback
        )
        val resultC = generatorService.generateWithDebug(topic, null, contextC)

        val response = InstagramNegativeImpactTestResult(
            topic = topic,
            analysis = NegativeImpactAnalysis(
                description = "Test mierzy wpływ NEGATIVE_EXAMPLES na styl generowanych postów. " +
                    "Temat '$topic' przy agresywnych przykładach POSITIVE prowokuje nachalny język sprzedażowy.",
                expectedBehavior = "Wariant A (eleganckie POSITIVE + agresywne NEGATIVE) ≈ Wariant B (bez NEGATIVE). " +
                    "Oba powinny być eleganckie. " +
                    "Wariant C (agresywne jako POSITIVE) powinien być agresywny. " +
                    "Jeśli A ma MNIEJ wykrzykników i ALL CAPS niż C → negatywy działają skutecznie."
            ),
            variantA = buildVariant("A: Eleganckie POSITIVE + agresywne NEGATIVE", elegantExamples, aggressiveExamples, resultA.parsed),
            variantB = buildVariant("B: Eleganckie POSITIVE, brak NEGATIVE (baseline)", elegantExamples, emptyList(), resultB.parsed),
            variantC = buildVariant("C: Agresywne POSITIVE, brak NEGATIVE (kontrast)", aggressiveExamples, emptyList(), resultC.parsed),
            conclusion = buildConclusion(resultA.parsed, resultB.parsed, resultC.parsed)
        )

        ResponseEntity.ok(response)
    }

    // ── Pomocnicze metryki ──────────────────────────────────────────────────────

    private fun buildVariant(
        label: String,
        positives: List<String>,
        negatives: List<String>,
        result: InstagramPostResult
    ): NegativeImpactVariant = NegativeImpactVariant(
        label = label,
        positiveExamples = positives,
        negativeExamples = negatives,
        result = result,
        metrics = computeStyleMetrics(result.content)
    )

    private fun computeStyleMetrics(text: String): StyleMetrics {
        val words = text.split("\\s+".toRegex())
        val allCapsWords = words.filter { word ->
            word.length > 2 && word == word.uppercase() && word.any { it.isLetter() }
        }
        val upperCount = text.count { it.isUpperCase() }
        val letterCount = text.count { it.isLetter() }.coerceAtLeast(1)

        val aggressiveKeywordList = listOf(
            "mega", "szok", "okazja", "tanio", "kup teraz",
            "nie przegap", "ostatnie", "musisz", "za pół ceny",
            "najlepsza", "uwierzysz", "promocja", "gratis", "wyprzedaż"
        )
        val foundKeywords = aggressiveKeywordList.filter { text.lowercase().contains(it) }

        return StyleMetrics(
            exclamationMarks = text.count { it == '!' },
            upperCaseRatio = upperCount.toDouble() / letterCount,
            wordCount = words.size,
            hasAllCapsWords = allCapsWords.isNotEmpty(),
            aggressiveKeywords = foundKeywords
        )
    }

    private fun buildConclusion(a: InstagramPostResult, b: InstagramPostResult, c: InstagramPostResult): String {
        val metricsA = computeStyleMetrics(a.content)
        val metricsB = computeStyleMetrics(b.content)
        val metricsC = computeStyleMetrics(c.content)

        return buildString {
            appendLine("=== AUTOMATYCZNA ANALIZA STYLU ===")
            appendLine("Wykrzykniki:      A=${metricsA.exclamationMarks}, B=${metricsB.exclamationMarks}, C=${metricsC.exclamationMarks}")
            appendLine("ALL CAPS słowa:   A=${metricsA.hasAllCapsWords}, B=${metricsB.hasAllCapsWords}, C=${metricsC.hasAllCapsWords}")
            appendLine("Upper ratio:      A=${"%.2f".format(metricsA.upperCaseRatio)}, B=${"%.2f".format(metricsB.upperCaseRatio)}, C=${"%.2f".format(metricsC.upperCaseRatio)}")
            appendLine("Agresywne słowa:  A=${metricsA.aggressiveKeywords}, B=${metricsB.aggressiveKeywords}, C=${metricsC.aggressiveKeywords}")
            appendLine()

            if (metricsC.exclamationMarks > metricsA.exclamationMarks &&
                metricsC.exclamationMarks > metricsB.exclamationMarks
            ) {
                appendLine("✅ WNIOSEK: Wariant C (agresywne POSITIVE) ma WIĘCEJ wykrzykników niż A i B.")
                appendLine("   → Pozytywne przykłady STERUJĄ stylem generowania.")
            }

            if (metricsA.exclamationMarks <= metricsB.exclamationMarks) {
                appendLine("✅ WNIOSEK: Wariant A (z negatywami) ma ≤ wykrzykników niż B (bez negatywów).")
                appendLine("   → Negatywne przykłady SKUTECZNIE ograniczają agresywny styl.")
            } else {
                appendLine("⚠️  UWAGA: Wariant A ma WIĘCEJ wykrzykników niż B.")
                appendLine("   → W tym przebiegu negatywy nie dały mierzalnego efektu.")
                appendLine("   → LLM jest niedeterministyczny przy temperature=0.7 — powtórz test.")
            }
        }
    }
}

// ── Globalny handler wyjątków ─────────────────────────────────────────────────

/**
 * Obsługuje wyjątki rzucone przez moduł AI i zwraca spójną odpowiedź błędu.
 * Ograniczony do [InstagramPostGenerationController] — nie wpływa na inne kontrolery.
 */
@RestControllerAdvice(assignableTypes = [InstagramPostGenerationController::class])
class InstagramAiExceptionHandler {

    private val logger = LoggerFactory.getLogger(InstagramAiExceptionHandler::class.java)

    @ExceptionHandler(InstagramPostGenerationException::class)
    fun handleGenerationError(ex: InstagramPostGenerationException): ResponseEntity<InstagramAiErrorResponse> {
        logger.error("Post generation failed: {}", ex.message)
        return ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(InstagramAiErrorResponse(error = "GENERATION_FAILED", message = ex.message ?: "Błąd generowania posta"))
    }

    @ExceptionHandler(InstagramPostClassificationException::class)
    fun handleClassificationError(ex: InstagramPostClassificationException): ResponseEntity<InstagramAiErrorResponse> {
        logger.error("Post classification failed: {}", ex.message)
        return ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(InstagramAiErrorResponse(error = "CLASSIFICATION_FAILED", message = ex.message ?: "Błąd klasyfikacji posta"))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleValidationError(ex: IllegalArgumentException): ResponseEntity<InstagramAiErrorResponse> {
        logger.warn("Validation error: {}", ex.message)
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(InstagramAiErrorResponse(error = "VALIDATION_ERROR", message = ex.message ?: "Nieprawidłowe żądanie"))
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpectedError(ex: Exception): ResponseEntity<InstagramAiErrorResponse> {
        logger.error("Unexpected error in Instagram AI module", ex)
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(InstagramAiErrorResponse(error = "INTERNAL_ERROR", message = "Wystąpił nieoczekiwany błąd: ${ex.message}"))
    }
}
