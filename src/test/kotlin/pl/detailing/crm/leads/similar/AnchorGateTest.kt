package pl.detailing.crm.leads.similar

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pl.detailing.crm.leads.similar.pricing.AnchorGate
import pl.detailing.crm.leads.similar.pricing.CompClass
import pl.detailing.crm.leads.similar.pricing.CompEvaluation
import pl.detailing.crm.leads.similar.pricing.GateThresholds
import pl.detailing.crm.service.taxonomy.ServiceFamily
import pl.detailing.crm.service.taxonomy.ServiceOperation
import pl.detailing.crm.service.taxonomy.ServicePart
import pl.detailing.crm.service.taxonomy.ServiceScope
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Bramki dopuszczenia kotwicy cenowej — czysta funkcja, więc te testy chodzą
 * w milisekundach, bez bazy i bez modelu.
 *
 * Trzy zasady, których pilnują:
 *  1. BRAK DANYCH ≠ ZERO: nieostemplowana kwota, nieznana kotwica i UNKNOWN
 *     na osi POMIJAJĄ bramkę, nigdy nie odrzucają.
 *  2. Konflikt ZNANYCH danych odrzuca TWARDO — bez degradacji do „podobnej".
 *  3. Metryki wychodzą na zewnątrz także dla odrzuconych — dziennik decyzji
 *     jest częścią kontraktu, nie efektem ubocznym.
 */
class AnchorGateTest {

    private val visitId = UUID.randomUUID()
    private val studio = UUID.randomUUID()
    private val now = Instant.parse("2026-09-15T12:00:00Z")

    private fun candidate(
        totalGross: Long = 0,
        happenedAt: Instant? = now.minus(60, ChronoUnit.DAYS),
        brandKey: String? = "bmw",
        modelKey: String? = "seria 5"
    ) = VisitIndexStateEntity(
        visitId = visitId,
        studioId = studio,
        fingerprint = "x",
        brandKey = brandKey,
        modelKey = modelKey,
        sizeSegment = "E",
        marketTier = "PREMIUM",
        happenedAt = happenedAt,
        sourceUpdatedAt = Instant.EPOCH,
        totalGross = totalGross
    )

    private fun signature(
        nameKey: String = "oklejenie ppf",
        family: ServiceFamily = ServiceFamily.PPF,
        scope: ServiceScope = ServiceScope.UNKNOWN,
        operation: ServiceOperation = ServiceOperation.UNKNOWN,
        part: ServicePart = ServicePart.UNKNOWN,
        linePriceGross: Long = 0,
        lineCount: Int = 1
    ) = VisitServiceSignatureEntity(
        visitId = visitId,
        studioId = studio,
        nameKey = nameKey,
        family = family.name,
        scope = scope.name,
        operation = operation.name,
        part = part.name,
        linePriceGross = linePriceGross,
        lineCount = lineCount
    )

    private fun intent(
        families: Set<ServiceFamily> = setOf(ServiceFamily.PPF),
        matched: Set<String> = emptySet(),
        scope: ServiceScope = ServiceScope.UNKNOWN,
        needs: List<WorkNeed> = emptyList(),
        anchorGross: Long? = null
    ) = LeadServiceIntent(
        status = ServiceIntentStatus.MATCHED,
        families = families,
        matchedNameKeys = matched,
        scope = scope,
        needs = needs,
        anchorGross = anchorGross
    )

    private fun evaluate(
        candidate: VisitIndexStateEntity,
        signatures: List<VisitServiceSignatureEntity>,
        intent: LeadServiceIntent,
        thresholds: GateThresholds = GateThresholds()
    ) = AnchorGate.evaluate(candidate, signatures, intent, "bmw", "seria 5", "E", now, thresholds)

    // ── Bramka skali ─────────────────────────────────────────────────────────

    @Test
    fun `kandydat dwadziescia razy tanszy od kotwicy odpada na bramce skali`() {
        val evaluation = evaluate(
            candidate(totalGross = 85_000),
            listOf(signature()),
            intent(anchorGross = 1_845_000)
        )
        assertEquals(CompClass.REJECTED, evaluation.compClass)
        assertEquals(CompEvaluation.REJECT_SCALE_MISMATCH, evaluation.rejectCode)
        // Metryka wychodzi także dla odrzuconego — dziennik musi umieć powiedzieć „ile brakowało".
        assertTrue(evaluation.priceRatio!! < 0.1)
    }

    /** Regresja z przeglądu planu: basePriceGross = 0 przy requireManualPrice. */
    @Test
    fun `brak kotwicy nie kasuje kandydatow — bramka skali sie nie uruchamia`() {
        val evaluation = evaluate(
            candidate(totalGross = 85_000),
            listOf(signature()),
            intent(anchorGross = null)
        )
        assertNotEquals(CompClass.REJECTED, evaluation.compClass)
        assertNull(evaluation.priceRatio)
    }

    /** Okno przestemplowania po V129: wiersz z totalGross = 0 to brak danych, nie darmowa robota. */
    @Test
    fun `nieostemplowana kwota omija bramke skali, nie odpada`() {
        val evaluation = evaluate(
            candidate(totalGross = 0),
            listOf(signature()),
            intent(anchorGross = 1_845_000)
        )
        assertNotEquals(CompClass.REJECTED, evaluation.compClass)
    }

    @Test
    fun `duza kotwica zweza pasmo`() {
        // 45% kotwicy: w paśmie zwykłym [0.4; 2.5] przechodzi, w zwężonym [0.6; 1.8] odpada.
        val small = evaluate(
            candidate(totalGross = 45_000),
            listOf(signature()),
            intent(anchorGross = 100_000)
        )
        assertNotEquals(CompClass.REJECTED, small.compClass)

        val big = evaluate(
            candidate(totalGross = 900_000),
            listOf(signature()),
            intent(anchorGross = 2_000_000)
        )
        assertEquals(CompEvaluation.REJECT_SCALE_MISMATCH, big.rejectCode)
    }

    // ── Bramki osi: rzemiosło i część ────────────────────────────────────────

    /** Sedno przypadku 2: czyszczenie nie staje się naprawą przez wspólną rodzinę INTERIOR. */
    @Test
    fun `czyszczenie odpada na bramce operacji przy potrzebie naprawy`() {
        val evaluation = evaluate(
            candidate(totalGross = 85_000),
            listOf(
                signature(
                    nameKey = "wnetrze rozszerzone", family = ServiceFamily.INTERIOR,
                    operation = ServiceOperation.CLEAN, part = ServicePart.CABIN
                )
            ),
            intent(
                families = setOf(ServiceFamily.INTERIOR),
                needs = listOf(WorkNeed(ServiceOperation.REPAIR, ServicePart.SEAT, ServiceScope.UNKNOWN)),
                anchorGross = 59_999
            )
        )
        assertEquals(CompClass.REJECTED, evaluation.compClass)
        assertEquals(CompEvaluation.REJECT_OPERATION_MISMATCH, evaluation.rejectCode)
    }

    /** Tapicerka to jedno rzemiosło: comp z boczka drzwi jest uczciwą kotwicą dla fotela. */
    @Test
    fun `naprawa boczka drzwi przechodzi przy potrzebie naprawy fotela`() {
        val evaluation = evaluate(
            candidate(totalGross = 70_000),
            listOf(
                signature(
                    nameKey = "naprawa tapicerki drzwi", family = ServiceFamily.INTERIOR,
                    operation = ServiceOperation.REPAIR, part = ServicePart.DOOR_PANEL
                )
            ),
            intent(
                families = setOf(ServiceFamily.INTERIOR),
                needs = listOf(WorkNeed(ServiceOperation.REPAIR, ServicePart.SEAT, ServiceScope.UNKNOWN)),
                anchorGross = 59_999
            )
        )
        assertNotEquals(CompClass.REJECTED, evaluation.compClass)
    }

    /** Sedno przypadku 1 po ostemplowaniu osi: próg bagażnika to nie full body. */
    @Test
    fun `folia na elemencie odpada na bramce czesci przy potrzebie full body`() {
        val evaluation = evaluate(
            candidate(totalGross = 85_000),
            listOf(
                signature(
                    nameKey = "zabezpieczenie progu bagaznika folia ppf", family = ServiceFamily.PPF,
                    operation = ServiceOperation.APPLY_FILM, part = ServicePart.TRIM_PIECE
                )
            ),
            intent(
                needs = listOf(WorkNeed(ServiceOperation.APPLY_FILM, ServicePart.FULL_BODY, ServiceScope.FULL)),
                anchorGross = null
            )
        )
        assertEquals(CompClass.REJECTED, evaluation.compClass)
        assertEquals(CompEvaluation.REJECT_SURFACE_MISMATCH, evaluation.rejectCode)
    }

    /** UNKNOWN po którejkolwiek stronie POMIJA bramkę osi — brak danych nie jest konfliktem. */
    @Test
    fun `nieznana operacja kandydata nie dyskwalifikuje`() {
        val evaluation = evaluate(
            candidate(totalGross = 70_000),
            listOf(signature(operation = ServiceOperation.UNKNOWN, part = ServicePart.UNKNOWN)),
            intent(needs = listOf(WorkNeed(ServiceOperation.APPLY_FILM, ServicePart.FULL_BODY, ServiceScope.FULL)))
        )
        assertNotEquals(CompClass.REJECTED, evaluation.compClass)
    }

    // ── Skupienie liczone na kwotach ─────────────────────────────────────────

    @Test
    fun `robota utopiona kwotowo w wiekszym zleceniu odpada`() {
        // Korekta za 400 zł w zleceniu za 19 000 zł: focus kwotowy 0,021 — zlecenie
        // jednopozycyjnie „pasuje", ale jego kwota mówi o zupełnie innej robocie.
        val evaluation = evaluate(
            candidate(totalGross = 1_900_000),
            listOf(
                signature(
                    nameKey = "korekta lakieru", family = ServiceFamily.CORRECTION_POLISH,
                    linePriceGross = 40_000
                ),
                signature(
                    nameKey = "folia ppf full body", family = ServiceFamily.PPF,
                    linePriceGross = 1_860_000
                )
            ),
            intent(
                families = setOf(ServiceFamily.CORRECTION_POLISH),
                matched = setOf("korekta lakieru"),
                anchorGross = 45_000
            )
        )
        assertEquals(CompClass.REJECTED, evaluation.compClass)
        assertEquals(CompEvaluation.REJECT_VALUE_FOCUS, evaluation.rejectCode)
    }

    // ── Wiek ─────────────────────────────────────────────────────────────────

    @Test
    fun `zlecenie starsze niz dwa lata odpada, roczne dostaje etykiete`() {
        val tooOld = evaluate(
            candidate(totalGross = 70_000, happenedAt = now.minus(26 * 30, ChronoUnit.DAYS)),
            listOf(signature()),
            intent()
        )
        assertEquals(CompEvaluation.REJECT_TOO_OLD, tooOld.rejectCode)

        val aged = evaluate(
            candidate(totalGross = 70_000, happenedAt = now.minus(15 * 30, ChronoUnit.DAYS)),
            listOf(signature()),
            intent()
        )
        assertNotEquals(CompClass.REJECTED, aged.compClass)
        assertTrue(aged.agedLabel)

        val fresh = evaluate(
            candidate(totalGross = 70_000, happenedAt = now.minus(90, ChronoUnit.DAYS)),
            listOf(signature()),
            intent()
        )
        assertFalse(fresh.agedLabel)
    }

    // ── Klasa compa ──────────────────────────────────────────────────────────

    @Test
    fun `dokladna pozycja cennika to DIRECT, sama rodzina to ADJUSTED`() {
        val direct = evaluate(
            candidate(totalGross = 70_000),
            listOf(signature(nameKey = "oklejenie ppf")),
            intent(matched = setOf("oklejenie ppf"))
        )
        assertEquals(CompClass.DIRECT, direct.compClass)

        val adjusted = evaluate(
            candidate(totalGross = 70_000),
            listOf(signature(nameKey = "inna nazwa tej roboty")),
            intent(families = setOf(ServiceFamily.PPF))
        )
        assertEquals(CompClass.ADJUSTED, adjusted.compClass)
    }

    @Test
    fun `cena odniesienia to linie pasujacej roboty, a bez nich kwota zlecenia`() {
        val withLines = evaluate(
            candidate(totalGross = 100_000),
            listOf(signature(nameKey = "oklejenie ppf", linePriceGross = 60_000)),
            intent(matched = setOf("oklejenie ppf"))
        )
        assertEquals(60_000L, withLines.referenceGross)

        val withoutLines = evaluate(
            candidate(totalGross = 100_000),
            listOf(signature(nameKey = "oklejenie ppf", linePriceGross = 0)),
            intent(matched = setOf("oklejenie ppf"))
        )
        assertEquals(100_000L, withoutLines.referenceGross)
    }
}
