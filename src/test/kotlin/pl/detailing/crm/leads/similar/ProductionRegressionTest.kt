package pl.detailing.crm.leads.similar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.leads.similar.pricing.AnchorGate
import pl.detailing.crm.leads.similar.pricing.CompClass
import pl.detailing.crm.leads.similar.pricing.CompEvaluation
import pl.detailing.crm.leads.similar.pricing.GateThresholds
import pl.detailing.crm.leads.similar.pricing.PriceBand
import pl.detailing.crm.service.taxonomy.ServiceFamily
import pl.detailing.crm.service.taxonomy.ServiceOperation
import pl.detailing.crm.service.taxonomy.ServicePart
import pl.detailing.crm.service.taxonomy.ServiceScope
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * OBA INCYDENTY PRODUKCYJNE jako testy regresji — dosłownie te liczby i te nazwy,
 * które trafiły na ekran właściciela (docs/similar-visits-redesign.md, wstęp).
 *
 * Przypadek 1: „PPF na całe auto, BMW Serii 5 (G60)", kotwica 18 450 zł — sekcja
 * podpowiedziała folię na progu bagażnika za 850 zł, polerowanie za 400 zł
 * i demontaż dokładki za 900 zł.
 *
 * Przypadek 2: „naprawa tapicerki fotela kierowcy, VW Arteon R" — sekcja
 * podpowiedziała mycie detailingowe z wnętrzem za 850 zł, a sugestia usług
 * pozycję „Naprawa tapicerki DRZWI" za 599,99 zł jako pewną cenę z cennika.
 *
 * Cała warstwa decydująca o szkodzie jest czystą funkcją, więc te testy są
 * deterministyczne i chodzą w milisekundach — bez bazy i bez API.
 */
class ProductionRegressionTest {

    private val studio = UUID.randomUUID()
    private val now = Instant.parse("2026-09-15T12:00:00Z")
    private val thresholds = GateThresholds()

    private fun candidate(visitId: UUID, brandKey: String, modelKey: String, totalGross: Long) =
        VisitIndexStateEntity(
            visitId = visitId,
            studioId = studio,
            fingerprint = "x",
            brandKey = brandKey,
            modelKey = modelKey,
            sizeSegment = "E",
            marketTier = "PREMIUM",
            happenedAt = now.minus(14, ChronoUnit.DAYS),
            sourceUpdatedAt = Instant.EPOCH,
            totalGross = totalGross
        )

    private fun signature(
        visitId: UUID,
        nameKey: String,
        family: ServiceFamily,
        operation: ServiceOperation = ServiceOperation.UNKNOWN,
        part: ServicePart = ServicePart.UNKNOWN,
        scope: ServiceScope = ServiceScope.UNKNOWN
    ) = VisitServiceSignatureEntity(
        visitId = visitId,
        studioId = studio,
        nameKey = nameKey,
        family = family.name,
        scope = scope.name,
        operation = operation.name,
        part = part.name
    )

    // ═══ PRZYPADEK 1: PPF full body, BMW Seria 5 ═══════════════════════════════

    private val ppfIntent = LeadServiceIntent(
        status = ServiceIntentStatus.MATCHED,
        families = setOf(ServiceFamily.PPF),
        matchedNameKeys = setOf("pakiet folii ppf - full body"),
        scope = ServiceScope.FULL,
        needs = listOf(WorkNeed(ServiceOperation.APPLY_FILM, ServicePart.FULL_BODY, ServiceScope.FULL)),
        anchorGross = 1_845_000
    )

    private fun evaluate(candidate: VisitIndexStateEntity, sigs: List<VisitServiceSignatureEntity>, intent: LeadServiceIntent) =
        AnchorGate.evaluate(candidate, sigs, intent, "bmw", "seria 5", "E", now, thresholds)

    /** VIS-2026-00106: trzy folie na elementach za łącznie 850 zł — 22× taniej niż kotwica. */
    @Test
    fun `case1 folia na progu za 850 zl odpada na bramce skali niezaleznie od osi`() {
        val visit = UUID.randomUUID()
        val evaluation = evaluate(
            candidate(visit, "bmw", "seria 5", totalGross = 85_000),
            listOf(
                signature(visit, "przyciemnienie tylnych lamp ppf", ServiceFamily.PPF),
                signature(visit, "zabezpieczenie progu bagaznika folia ppf", ServiceFamily.PPF),
                signature(visit, "oklejenie wyswietlacza folia mat", ServiceFamily.PPF)
            ),
            ppfIntent
        )
        assertEquals(CompClass.REJECTED, evaluation.compClass)
        assertEquals(CompEvaluation.REJECT_SCALE_MISMATCH, evaluation.rejectCode)
    }

    /** Po ostemplowaniu osi ten sam kandydat odpada już na CZĘŚCI auta — jeszcze przed ceną. */
    @Test
    fun `case1 folia na progu odpada na bramce czesci, gdy osie sa ostemplowane`() {
        val visit = UUID.randomUUID()
        val evaluation = evaluate(
            candidate(visit, "bmw", "seria 5", totalGross = 85_000),
            listOf(
                signature(
                    visit, "zabezpieczenie progu bagaznika folia ppf", ServiceFamily.PPF,
                    operation = ServiceOperation.APPLY_FILM, part = ServicePart.TRIM_PIECE
                )
            ),
            // Bez kotwicy — celowo: sama oś części ma wystarczyć.
            ppfIntent.copy(anchorGross = null)
        )
        assertEquals(CompClass.REJECTED, evaluation.compClass)
        assertEquals(CompEvaluation.REJECT_SURFACE_MISMATCH, evaluation.rejectCode)
    }

    /** VIS-2026-00100: „Usunięcie rys" za 400 zł — dawna ranga SAME_MODEL_OTHER_SERVICE nie istnieje. */
    @Test
    fun `case1 polerowanie za 400 zl nie ma juz rangi, do ktorej moglo wejsc`() {
        val visit = UUID.randomUUID()
        val evaluation = evaluate(
            candidate(visit, "bmw", "seria 5", totalGross = 40_000),
            listOf(signature(visit, "usuniecie rys i uzupelnienie powloki", ServiceFamily.CORRECTION_POLISH)),
            ppfIntent
        )
        assertEquals(CompClass.REJECTED, evaluation.compClass)
        // Inna rodzina roboty — odpada na pokryciu, nie dopiero na cenie.
        assertEquals(CompEvaluation.REJECT_WORK_MISMATCH, evaluation.rejectCode)
    }

    /** VIS-2026-00021: demontaż i montaż dokładki za 900 zł. */
    @Test
    fun `case1 demontaz dokladki za 900 zl odpada`() {
        val visit = UUID.randomUUID()
        val evaluation = evaluate(
            candidate(visit, "bmw", "seria 5", totalGross = 90_000),
            listOf(
                signature(visit, "demontaz przedniej dokladki", ServiceFamily.OTHER),
                signature(visit, "montaz przedniej dokladki", ServiceFamily.OTHER)
            ),
            ppfIntent
        )
        assertEquals(CompClass.REJECTED, evaluation.compClass)
    }

    /** Trzy produkcyjne podpowiedzi jako pasmo: jednostronne — etykieta, nie przedział. */
    @Test
    fun `case1 zestaw jednostronny jest oznaczony, nie pokazany jako przedzial`() {
        val band = PriceBand.of(listOf(85_000, 40_000, 90_000), anchorGross = 1_845_000)!!
        assertTrue(band.oneSided, "Same realizacje wielokrotnie mniejsze od kotwicy nie są przedziałem ceny")
    }

    // ═══ PRZYPADEK 2: naprawa tapicerki fotela, VW Arteon ═════════════════════

    private val upholsteryIntent = LeadServiceIntent(
        status = ServiceIntentStatus.MATCHED,
        families = setOf(ServiceFamily.INTERIOR),
        matchedNameKeys = emptySet(),
        scope = ServiceScope.UNKNOWN,
        needs = listOf(WorkNeed(ServiceOperation.REPAIR, ServicePart.SEAT, ServiceScope.UNKNOWN)),
        anchorGross = 59_999
    )

    private fun evaluateArteon(candidate: VisitIndexStateEntity, sigs: List<VisitServiceSignatureEntity>, intent: LeadServiceIntent) =
        AnchorGate.evaluate(candidate, sigs, intent, "volkswagen", "arteon", "D", now, thresholds)

    private fun arteonCleaningVisit(visit: UUID) = listOf(
        signature(
            visit, "mycie detailingowe", ServiceFamily.WASH,
            operation = ServiceOperation.CLEAN
        ),
        signature(
            visit, "wnetrze rozszerzone", ServiceFamily.INTERIOR,
            operation = ServiceOperation.CLEAN, part = ServicePart.CABIN
        ),
        signature(
            visit, "polerowanie chromow", ServiceFamily.CORRECTION_POLISH,
            operation = ServiceOperation.CORRECT
        )
    )

    /** VIS-2026-00092: mycie z wnętrzem za 850 zł — odpada na RZEMIOŚLE, nie degraduje się. */
    @Test
    fun `case2 mycie detailingowe odpada na bramce operacji`() {
        val visit = UUID.randomUUID()
        val evaluation = evaluateArteon(
            candidate(visit, "volkswagen", "arteon", totalGross = 85_000),
            arteonCleaningVisit(visit),
            upholsteryIntent
        )
        assertEquals(CompClass.REJECTED, evaluation.compClass)
        assertEquals(CompEvaluation.REJECT_OPERATION_MISMATCH, evaluation.rejectCode)
    }

    /**
     * UCZCIWOŚĆ ARYTMETYCZNA (z przeglądu planu): kotwica 599,99 zł, kandydat 850 zł
     * — iloraz 1,42, ŚRODEK pasma. Sama bramka cenowa przypadku 2 NIE zamyka;
     * zamyka go dopiero oś operacji. Ten test przybija, że osie są konieczne,
     * a nie „nice to have" — bez nich kandydat przechodzi.
     */
    @Test
    fun `case2 bez osi operacji bramka cenowa NIE wystarcza`() {
        val visit = UUID.randomUUID()
        val evaluation = evaluateArteon(
            candidate(visit, "volkswagen", "arteon", totalGross = 85_000),
            listOf(
                // Stare stemple: osie nieznane, jest tylko rodzina INTERIOR.
                signature(visit, "mycie detailingowe", ServiceFamily.WASH),
                signature(visit, "wnetrze rozszerzone", ServiceFamily.INTERIOR),
                signature(visit, "polerowanie chromow", ServiceFamily.CORRECTION_POLISH)
            ),
            upholsteryIntent.copy(needs = emptyList())
        )
        assertNotEquals(CompClass.REJECTED, evaluation.compClass)
        assertTrue(evaluation.priceRatio!! in 1.3..1.6, "iloraz 850/599,99 ≈ 1,42 siedzi w środku pasma")
    }

    /** Werdykt NEAR_MISS (drzwi zamiast fotela) wyłącza całą ścieżkę cenową kandydatów. */
    @Test
    fun `case2 catalog near miss nie wpuszcza zadnego kandydata`() {
        val visit = UUID.randomUUID()
        val evaluation = evaluateArteon(
            candidate(visit, "volkswagen", "arteon", totalGross = 85_000),
            arteonCleaningVisit(visit),
            upholsteryIntent.copy(status = ServiceIntentStatus.CATALOG_NEAR_MISS)
        )
        assertEquals(CompClass.REJECTED, evaluation.compClass)
        assertEquals(CompEvaluation.REJECT_NO_INTENT, evaluation.rejectCode)
    }
}
