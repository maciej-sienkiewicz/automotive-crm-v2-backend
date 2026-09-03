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
import pl.detailing.crm.instagram.ai.ratelimit.InstagramAiRateLimitExceededException
import pl.detailing.crm.instagram.ai.ratelimit.InstagramAiRateLimiter
import pl.detailing.crm.instagram.ai.rating.InstagramGeneratedPostService
import pl.detailing.crm.instagram.ai.rules.InstagramStyleRuleService
import pl.detailing.crm.role.permission.RequiresPermission
import pl.detailing.crm.role.domain.Permission
import pl.detailing.crm.subscription.entitlement.capability.CapabilityKey
import pl.detailing.crm.subscription.entitlement.capability.RequiresCapability
import java.util.UUID

/**
 * Kontroler REST dla modułu generowania postów Instagram za pomocą AI.
 *
 * Wszystkie endpointy wymagają uwierzytelnienia – studioId jest pobierane z kontekstu
 * bezpieczeństwa Spring Security, co zapewnia izolację per-tenant.
 *
 * Ścieżka bazowa: /api/v1/instagram/ai
 */
@RequiresPermission(Permission.MARKETING_MANAGE)
@RequiresCapability(CapabilityKey.INSTAGRAM_MONITOR)
@RestController
@RequestMapping("/api/v1/instagram/ai")
class InstagramPostGenerationController(
    private val inspirationService: InstagramInspirationService,
    private val generatorService: InstagramPostGeneratorService,
    private val styleRuleService: InstagramStyleRuleService,
    private val generatedPostService: InstagramGeneratedPostService,
    private val rateLimiter: InstagramAiRateLimiter,
    @org.springframework.beans.factory.annotation.Value("\${instagram.ai.debug-endpoints.enabled:false}")
    private val debugEndpointsEnabled: Boolean
) {
    private val logger = LoggerFactory.getLogger(InstagramPostGenerationController::class.java)

    /** Endpointy diagnostyczne sa domyslnie wylaczone na produkcji. */
    private fun requireDebugEnabled() {
        if (!debugEndpointsEnabled) {
            throw pl.detailing.crm.shared.EntityNotFoundException("Nie znaleziono zasobu.")
        }
    }

    // ── Generowanie posta ──────────────────────────────────────────────────────

    /**
     * POST /api/v1/instagram/ai/generate
     *
     * Generuje nowy post Instagram dla zalogowanego studia.
     *
     * Przepływ:
     *   1. Limit per studio (przed retrievalem i przed jakimkolwiek wywołaniem LLM —
     *      odrzucone żądanie nie może kosztować ani jednego tokenu)
     *   2. Reguły stylistyczne: aktywne reguły studia + reguły ad-hoc z żądania (styleNotes)
     *   3. Retrieval: przykłady few-shot z VectorStore (własne posty mają pierwszeństwo)
     *   4. Pętla generuj → weryfikuj → popraw (max 3 rundy weryfikacji)
     *   5. Zapis posta razem z raportem weryfikacji i snapshotem reguł — ZAWSZE
     *
     * Kształt żądania pozostaje bez zmian; odpowiedź jest ROZSZERZONA o postId,
     * verificationPassed, failedRules i iterations — pole content zostaje.
     */
    @PostMapping("/generate")
    fun generatePost(
        @RequestBody request: GenerateInstagramPostRequest
    ): ResponseEntity<GenerateInstagramPostResponse> = runBlocking {
        require(request.topic.isNotBlank()) { "Temat posta nie może być pusty" }

        val principal = SecurityContextHelper.getCurrentUser()
        val rateLimit = rateLimiter.checkAndConsume(principal.studioId)

        logger.info(
            "Generate request: studioId={}, topic='{}', tone={}, length={}",
            principal.studioId, request.topic, request.postTone, request.postLength
        )

        // Reguły z bazy studia + reguły ad-hoc z żądania (pole styleNotes zostaje
        // dla kompatybilności ze starszym frontendem, który trzyma reguły u siebie).
        val adHocRules = request.styleNotes.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
        val rules = (styleRuleService.activeRuleTexts(principal.studioId) + adHocRules).distinct()

        val inspirationContext = inspirationService.getInspirationContext(
            topic = request.topic,
            studioId = principal.studioId,
            postTone = request.postTone,
            postLength = request.postLength,
            styleNotes = rules
        )

        val result = generatorService.generateVerified(
            topic = request.topic,
            additionalContext = request.context,
            inspirationContext = inspirationContext
        )

        val saved = generatedPostService.save(
            studioId = principal.studioId,
            topic = request.topic,
            additionalContext = request.context,
            requestedTone = request.postTone,
            requestedLength = request.postLength,
            result = result
        )

        logger.info(
            "Post generated: studioId={}, postId={}, verified={}, iterations={}",
            principal.studioId, saved.id, result.verificationPassed, result.iterations
        )

        ResponseEntity.ok()
            .header("X-RateLimit-Limit", rateLimit.limit.toString())
            .header("X-RateLimit-Remaining", rateLimit.remaining.toString())
            .body(
                GenerateInstagramPostResponse(
                    content = result.content,
                    postId = saved.id.toString(),
                    verificationPassed = result.verificationPassed,
                    failedRules = result.failedRules,
                    failedRuleDetails = result.verdicts
                        .filter { !it.passed }
                        .map { FailedRule(rule = it.ruleText, reason = it.violation ?: "") },
                    iterations = result.iterations
                )
            )
    }

    // ── Reguły stylistyczne ────────────────────────────────────────────────────

    /** GET /api/v1/instagram/ai/style-rules — wszystkie reguły studia (aktywne i wyłączone). */
    @GetMapping("/style-rules")
    fun listStyleRules(): ResponseEntity<List<StyleRuleResponse>> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(styleRuleService.list(principal.studioId))
    }

    /** POST /api/v1/instagram/ai/style-rules — dodaje regułę (max 20 aktywnych, max 500 znaków). */
    @PostMapping("/style-rules")
    fun createStyleRule(
        @RequestBody request: CreateStyleRuleRequest
    ): ResponseEntity<StyleRuleResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(styleRuleService.create(principal.studioId, request))
    }

    /** PUT /api/v1/instagram/ai/style-rules/{id} — zmiana treści i/lub aktywności reguły. */
    @PutMapping("/style-rules/{id}")
    fun updateStyleRule(
        @PathVariable id: UUID,
        @RequestBody request: UpdateStyleRuleRequest
    ): ResponseEntity<StyleRuleResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(styleRuleService.update(principal.studioId, id, request))
    }

    /** DELETE /api/v1/instagram/ai/style-rules/{id} */
    @DeleteMapping("/style-rules/{id}")
    fun deleteStyleRule(@PathVariable id: UUID): ResponseEntity<Void> {
        val principal = SecurityContextHelper.getCurrentUser()
        styleRuleService.delete(principal.studioId, id)
        return ResponseEntity.noContent().build()
    }

    // ── Historia i ocena wygenerowanych postów ─────────────────────────────────

    /** GET /api/v1/instagram/ai/posts?limit=20 — historia od najnowszych. */
    @GetMapping("/posts")
    fun listGeneratedPosts(
        @RequestParam(defaultValue = "20") limit: Int
    ): ResponseEntity<List<GeneratedPostResponse>> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(generatedPostService.history(principal.studioId, limit))
    }

    /**
     * POST /api/v1/instagram/ai/posts/{id}/rate
     *
     * Ocena zamyka pętlę uczenia — po zapisie post trafia do VectorStore jako wzorzec
     * (POSITIVE) albo antywzorzec (NEGATIVE) dla kolejnych generowań tego studia.
     * Komentarz ma sens wyłącznie przy ocenie negatywnej. Endpoint nie woła LLM
     * synchronicznie, więc nie podlega limitowi generowań.
     */
    @PostMapping("/posts/{id}/rate")
    fun rateGeneratedPost(
        @PathVariable id: UUID,
        @RequestBody request: RateGeneratedPostRequest
    ): ResponseEntity<GeneratedPostResponse> {
        val principal = SecurityContextHelper.getCurrentUser()
        return ResponseEntity.ok(generatedPostService.rate(principal.studioId, id, request))
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
        requireDebugEnabled()
        require(request.topic.isNotBlank()) { "Temat posta nie może być pusty" }

        val principal = SecurityContextHelper.getCurrentUser()

        val inspirationContext = inspirationService.getInspirationContext(
            topic = request.topic,
            studioId = principal.studioId,
            postTone = request.postTone,
            postLength = request.postLength,
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
        requireDebugEnabled()
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
            variantADebug.parsed.content, variantBDebug.parsed.content
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
        requireDebugEnabled()
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

    @ExceptionHandler(InstagramAiRateLimitExceededException::class)
    fun handleRateLimit(ex: InstagramAiRateLimitExceededException): ResponseEntity<InstagramAiErrorResponse> {
        logger.warn("Instagram AI rate limit hit: window={}, limit={}", ex.window, ex.limit)
        return ResponseEntity
            .status(HttpStatus.TOO_MANY_REQUESTS)
            .header("X-RateLimit-Limit", ex.limit.toString())
            .header("X-RateLimit-Remaining", "0")
            .header("Retry-After", ex.retryAfterSeconds.toString())
            .body(InstagramAiErrorResponse(error = "RATE_LIMITED", message = ex.message ?: "Przekroczono limit żądań."))
    }

    /**
     * Lokalny advice przechwytuje dla tego kontrolera także [Exception], więc bez tych
     * dwóch handlerów wyjątki biznesowe kończyłyby się tutaj kodem 500 zamiast 404/400.
     */
    @ExceptionHandler(pl.detailing.crm.shared.EntityNotFoundException::class)
    fun handleNotFound(ex: pl.detailing.crm.shared.EntityNotFoundException): ResponseEntity<InstagramAiErrorResponse> {
        logger.warn("Not found: {}", ex.message)
        return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(InstagramAiErrorResponse(error = "NOT_FOUND", message = ex.message ?: "Nie znaleziono zasobu."))
    }

    @ExceptionHandler(pl.detailing.crm.shared.ValidationException::class)
    fun handleBusinessValidation(ex: pl.detailing.crm.shared.ValidationException): ResponseEntity<InstagramAiErrorResponse> {
        logger.warn("Validation error: {}", ex.message)
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(InstagramAiErrorResponse(error = "VALIDATION_ERROR", message = ex.message ?: "Nieprawidłowe żądanie"))
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
