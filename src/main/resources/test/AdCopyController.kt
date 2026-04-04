package com.example.demo.adcopy.controller

import com.example.demo.adcopy.model.AbTestRequest
import com.example.demo.adcopy.model.AbTestResponse
import com.example.demo.adcopy.model.AbTestVariant
import com.example.demo.adcopy.model.AdCopyResponse
import com.example.demo.adcopy.model.AdGenerateRequest
import com.example.demo.adcopy.model.DebugAdCopyResponse
import com.example.demo.adcopy.model.ErrorResponse
import com.example.demo.adcopy.model.FallbackInfo
import com.example.demo.adcopy.model.InspirationContext
import com.example.demo.adcopy.model.NegativeImpactAnalysis
import com.example.demo.adcopy.model.NegativeImpactTestResponse
import com.example.demo.adcopy.model.NegativeImpactVariant
import com.example.demo.adcopy.model.StyleMetrics
import com.example.demo.adcopy.service.AdGeneratorService
import com.example.demo.adcopy.service.HeadlineInspirationService
import com.example.demo.adcopy.service.LlmEmptyResponseException
import com.example.demo.adcopy.service.LlmParsingException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/ads")
class AdCopyController(
    private val headlineInspirationService: HeadlineInspirationService,
    private val adGeneratorService: AdGeneratorService
) {
    private val logger = LoggerFactory.getLogger(AdCopyController::class.java)

    /**
     * POST /api/ads/generate
     * Generuje nowy nagłówek reklamowy na podstawie tematu i kontekstu użytkownika.
     */
    @PostMapping("/generate")
    suspend fun generateAdCopy(@RequestBody request: AdGenerateRequest): ResponseEntity<AdCopyResponse> {
        logger.info(
            "Received ad generation request: topic='{}', userId={}, contextLength={}",
            request.topic, request.userId, request.context?.length ?: 0
        )

        require(request.topic.isNotBlank()) { "Topic nie może być pusty" }
        require(request.userId > 0) { "userId musi być większe od 0" }

        // 1. Warstwa Retrieval – zbierz spersonalizowany kontekst
        val inspirationContext = headlineInspirationService.getInspirationContext(
            topic = request.topic,
            userId = request.userId,
            postTone = request.postTone,
            postLength = request.postLength,
            serviceType = request.serviceType,
            styleNotes = request.styleNotes ?: emptyList()
        )

        // 2. Warstwa Generowania – wygeneruj nagłówek
        val adCopy = adGeneratorService.generate(
            topic = request.topic,
            additionalContext = request.context,
            inspirationContext = inspirationContext
        )

        logger.info("Ad copy generated successfully for topic='{}'", request.topic)
        return ResponseEntity.ok(adCopy)
    }

    /**
     * POST /api/ads/debug-generate
     * Jak /generate, ale zwraca pełny prompt (system + user message) + raw LLM response.
     * Służy do weryfikacji, co dokładnie trafia do modelu.
     */
    @PostMapping("/debug-generate")
    suspend fun debugGenerate(@RequestBody request: AdGenerateRequest): ResponseEntity<DebugAdCopyResponse> {
        require(request.topic.isNotBlank()) { "Topic nie może być pusty" }
        require(request.userId > 0) { "userId musi być większe od 0" }

        val inspirationContext = headlineInspirationService.getInspirationContext(
            topic = request.topic,
            userId = request.userId,
            postTone = request.postTone,
            postLength = request.postLength,
            serviceType = request.serviceType,
            styleNotes = request.styleNotes ?: emptyList()
        )

        val debugResponse = adGeneratorService.generateWithDebug(
            topic = request.topic,
            additionalContext = request.context,
            inspirationContext = inspirationContext
        )

        return ResponseEntity.ok(debugResponse)
    }

    /**
     * POST /api/ads/ab-test
     * Test A/B: generuje DWA warianty dla tego samego tematu:
     *   - Wariant A: z negatywnymi przykładami (pełny przepływ)
     *   - Wariant B: BEZ negatywnych przykładów (puste NEGATIVE_EXAMPLES)
     * Pozwala porównać, czy negatywne przykłady faktycznie wpływają na wynik.
     */
    @PostMapping("/ab-test")
    suspend fun abTest(@RequestBody request: AbTestRequest): ResponseEntity<AbTestResponse> {
        require(request.topic.isNotBlank()) { "Topic nie może być pusty" }
        require(request.userId > 0) { "userId musi być większe od 0" }

        logger.info("Starting A/B test for topic='{}', userId={}", request.topic, request.userId)

        // Pobierz pełny kontekst inspiracji
        val fullContext = headlineInspirationService.getInspirationContext(
            topic = request.topic,
            userId = request.userId
        )

        // Wariant A: Z negatywnymi przykładami (pełny przepływ)
        logger.info("Generating Variant A (WITH negatives)...")
        val variantADebug = adGeneratorService.generateWithDebug(
            topic = request.topic,
            additionalContext = request.context,
            inspirationContext = fullContext
        )

        // Wariant B: BEZ negatywnych przykładów
        val contextWithoutNegatives = InspirationContext(
            positiveExamples = fullContext.positiveExamples,
            negativeExamples = emptyList(),
            requestedTone = fullContext.requestedTone,
            requestedLength = fullContext.requestedLength,
            fallbackInfo = fullContext.fallbackInfo
        )
        logger.info("Generating Variant B (WITHOUT negatives)...")
        val variantBDebug = adGeneratorService.generateWithDebug(
            topic = request.topic,
            additionalContext = request.context,
            inspirationContext = contextWithoutNegatives
        )

        val response = AbTestResponse(
            topic = request.topic,
            positiveExamplesUsed = fullContext.positiveExamples,
            negativeExamplesUsed = fullContext.negativeExamples,
            variantA = AbTestVariant(
                label = "Z NEGATYWNYMI przykładami (${fullContext.negativeExamples.size} szt.)",
                systemMessageExcerpt = variantADebug.systemMessage,
                result = variantADebug.parsed
            ),
            variantB = AbTestVariant(
                label = "BEZ negatywnych przykładów",
                systemMessageExcerpt = variantBDebug.systemMessage,
                result = variantBDebug.parsed
            ),
            verdict = "Porównaj headline/description/punchline obu wariantów. " +
                "Wariant A powinien UNIKAĆ stylu z NEGATIVE_EXAMPLES " +
                "(np. ALL CAPS, '!!!', agresywny ton sprzedażowy), " +
                "podczas gdy Wariant B nie ma takich ograniczeń."
        )

        logger.info(
            "A/B test completed. Variant A headline='{}', Variant B headline='{}'",
            variantADebug.parsed.headline, variantBDebug.parsed.headline
        )

        return ResponseEntity.ok(response)
    }

    /**
     * POST /api/ads/negative-impact-test
     * Kontrolowany test z HARDCODED przykładami — nie zależy od VectorStore.
     * 3 warianty:
     *   A: eleganckie POSITIVE + agresywne NEGATIVE → powinien unikać agresji
     *   B: eleganckie POSITIVE, brak NEGATIVE → baseline
     *   C: agresywne POSITIVE, brak NEGATIVE → powinien być agresywny
     * Automatycznie liczy metryki stylistyczne (!, CAPS, słowa kluczowe).
     */
    @PostMapping("/negative-impact-test")
    suspend fun negativeImpactTest(): ResponseEntity<NegativeImpactTestResponse> {
        logger.info("Starting negative impact test with hardcoded examples...")

        val topic = "Wielka wyprzedaż kawy – rabaty do 50%"

        val elegantExamples = listOf(
            "Cisza poranka. Aromat ziaren. Twój moment z kawą.",
            "Każde ziarno opowiada historię dalekiej plantacji",
            "Kawa, która nie krzyczy – szepcze z każdym łykiem",
            "Minimalizm w filiżance. Maksimum smaku.",
            "Dla tych, którzy cenią ciszę i dobry smak"
        )

        val aggressiveExamples = listOf(
            "MEGA OKAZJA!!! KAWA ZA PÓŁ CENY!!! KUP TERAZ!!!",
            "SZOK!!! NAJLEPSZA KAWA EVER!!! MUSISZ TO MIEĆ!!!",
            "NIE UWIERZYSZ JAKA TANIA!!! OSTATNIE SZTUKI!!!"
        )

        // Wariant A: eleganckie POSITIVE + agresywne NEGATIVE
        val noFallback = FallbackInfo.ideal()
        val contextA = InspirationContext(positiveExamples = elegantExamples, negativeExamples = aggressiveExamples, requestedTone = null, requestedLength = null, fallbackInfo = noFallback)
        val resultA = adGeneratorService.generateWithDebug(topic, null, contextA)

        // Wariant B: eleganckie POSITIVE, brak NEGATIVE (baseline)
        val contextB = InspirationContext(positiveExamples = elegantExamples, negativeExamples = emptyList(), requestedTone = null, requestedLength = null, fallbackInfo = noFallback)
        val resultB = adGeneratorService.generateWithDebug(topic, null, contextB)

        // Wariant C: agresywne jako POSITIVE, brak NEGATIVE
        val contextC = InspirationContext(positiveExamples = aggressiveExamples, negativeExamples = emptyList(), requestedTone = null, requestedLength = null, fallbackInfo = noFallback)
        val resultC = adGeneratorService.generateWithDebug(topic, null, contextC)

        val response = NegativeImpactTestResponse(
            topic = topic,
            analysis = NegativeImpactAnalysis(
                description = "Test mierzy wpływ NEGATIVE_EXAMPLES na styl generowanych nagłówków. " +
                    "Temat '$topic' celowo prowokuje agresywny język sprzedażowy.",
                expectedBehavior = "Wariant A (z negatywami) ≈ Wariant B (bez) – oba eleganckie. " +
                    "Wariant C (agresywne jako pozytywne) – powinien być agresywny. " +
                    "Jeśli A ma MNIEJ wykrzykników i ALL CAPS niż C → negatywy działają."
            ),
            variantA = buildVariant(
                "A: Eleganckie POSITIVE + agresywne NEGATIVE",
                elegantExamples, aggressiveExamples, resultA.parsed
            ),
            variantB = buildVariant(
                "B: Eleganckie POSITIVE, brak NEGATIVE (baseline)",
                elegantExamples, emptyList(), resultB.parsed
            ),
            variantC = buildVariant(
                "C: Agresywne jako POSITIVE, brak NEGATIVE (kontrast)",
                aggressiveExamples, emptyList(), resultC.parsed
            ),
            conclusion = buildConclusion(resultA.parsed, resultB.parsed, resultC.parsed)
        )

        return ResponseEntity.ok(response)
    }

    private fun buildVariant(
        label: String,
        positives: List<String>,
        negatives: List<String>,
        result: AdCopyResponse
    ): NegativeImpactVariant {
        val fullText = "${result.headline} ${result.description} ${result.punchline}"
        return NegativeImpactVariant(
            label = label,
            positiveExamples = positives,
            negativeExamples = negatives,
            result = result,
            metrics = computeMetrics(fullText)
        )
    }

    private fun computeMetrics(text: String): StyleMetrics {
        val words = text.split("\\s+".toRegex())
        val allCapsWords = words.filter { it.length > 2 && it == it.uppercase() && it.any { c -> c.isLetter() } }
        val upperCount = text.count { it.isUpperCase() }
        val letterCount = text.count { it.isLetter() }.coerceAtLeast(1)

        val aggressiveKeywords = listOf(
            "mega", "szok", "okazja", "tanio", "kup teraz",
            "nie przegap", "ostatnie", "musisz", "za pół ceny",
            "najlepsza", "uwierzysz", "promocja", "gratis"
        )
        val foundKeywords = aggressiveKeywords.filter { text.lowercase().contains(it) }

        return StyleMetrics(
            exclamationMarks = text.count { it == '!' },
            upperCaseRatio = upperCount.toDouble() / letterCount,
            wordCount = words.size,
            hasAllCapsWords = allCapsWords.isNotEmpty(),
            aggressiveKeywords = foundKeywords
        )
    }

    private fun buildConclusion(a: AdCopyResponse, b: AdCopyResponse, c: AdCopyResponse): String {
        val textA = "${a.headline} ${a.description} ${a.punchline}"
        val textB = "${b.headline} ${b.description} ${b.punchline}"
        val textC = "${c.headline} ${c.description} ${c.punchline}"

        val metricsA = computeMetrics(textA)
        val metricsB = computeMetrics(textB)
        val metricsC = computeMetrics(textC)

        val sb = StringBuilder()
        sb.appendLine("=== AUTOMATYCZNA ANALIZA ===")
        sb.appendLine("Wykrzykniki:      A=${metricsA.exclamationMarks}, B=${metricsB.exclamationMarks}, C=${metricsC.exclamationMarks}")
        sb.appendLine("ALL CAPS słowa:   A=${metricsA.hasAllCapsWords}, B=${metricsB.hasAllCapsWords}, C=${metricsC.hasAllCapsWords}")
        sb.appendLine("Upper ratio:      A=${"%.2f".format(metricsA.upperCaseRatio)}, B=${"%.2f".format(metricsB.upperCaseRatio)}, C=${"%.2f".format(metricsC.upperCaseRatio)}")
        sb.appendLine("Agresywne słowa:  A=${metricsA.aggressiveKeywords}, B=${metricsB.aggressiveKeywords}, C=${metricsC.aggressiveKeywords}")
        sb.appendLine()

        if (metricsC.exclamationMarks > metricsA.exclamationMarks &&
            metricsC.exclamationMarks > metricsB.exclamationMarks
        ) {
            sb.appendLine("✅ WNIOSEK: Wariant C (agresywne jako pozytywne) ma WIĘCEJ wykrzykników niż A i B.")
            sb.appendLine("   → Pozytywne przykłady STERUJĄ stylem generowania.")
        }

        if (metricsA.exclamationMarks <= metricsB.exclamationMarks) {
            sb.appendLine("✅ WNIOSEK: Wariant A (z negatywami) ma ≤ wykrzykników niż B (bez negatywów).")
            sb.appendLine("   → Negatywne przykłady pomagają UNIKAĆ agresywnego stylu.")
        } else {
            sb.appendLine("⚠️ UWAGA: Wariant A ma WIĘCEJ wykrzykników niż B.")
            sb.appendLine("   → W tym przebiegu negatywy nie dały mierzalnego efektu (LLM jest niedeterministyczny).")
            sb.appendLine("   → Uruchom test ponownie — przy temperature=0.7 wyniki się wahają.")
        }

        return sb.toString()
    }
}

/**
 * Globalny handler wyjątków dla modułu AdCopy.
 */
@RestControllerAdvice(assignableTypes = [AdCopyController::class])
class AdCopyExceptionHandler {

    private val logger = LoggerFactory.getLogger(AdCopyExceptionHandler::class.java)

    @ExceptionHandler(LlmEmptyResponseException::class)
    fun handleLlmEmptyResponse(ex: LlmEmptyResponseException): ResponseEntity<ErrorResponse> {
        logger.error("LLM empty response: {}", ex.message)
        return ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ErrorResponse(error = "LLM_EMPTY_RESPONSE", message = ex.message ?: "Brak odpowiedzi z LLM"))
    }

    @ExceptionHandler(LlmParsingException::class)
    fun handleLlmParsingError(ex: LlmParsingException): ResponseEntity<ErrorResponse> {
        logger.error("LLM parsing error: {}", ex.message, ex)
        return ResponseEntity
            .status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(ErrorResponse(error = "LLM_PARSING_ERROR", message = ex.message ?: "Błąd parsowania odpowiedzi LLM"))
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(ex: IllegalArgumentException): ResponseEntity<ErrorResponse> {
        logger.warn("Bad request: {}", ex.message)
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(ErrorResponse(error = "BAD_REQUEST", message = ex.message ?: "Nieprawidłowe żądanie"))
    }

    @ExceptionHandler(Exception::class)
    fun handleGenericError(ex: Exception): ResponseEntity<ErrorResponse> {
        logger.error("Unexpected error during ad generation", ex)
        return ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(
                ErrorResponse(
                    error = "INTERNAL_ERROR",
                    message = "Wystąpił nieoczekiwany błąd: ${ex.message}"
                )
            )
    }
}

