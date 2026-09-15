package pl.detailing.crm.leads.similar.pricing

import pl.detailing.crm.leads.similar.LeadServiceIntent
import pl.detailing.crm.leads.similar.MatchTier
import pl.detailing.crm.leads.similar.ServiceIntentStatus
import pl.detailing.crm.leads.similar.SimilarVisitMatcher
import pl.detailing.crm.leads.similar.VisitIndexStateEntity
import pl.detailing.crm.leads.similar.VisitServiceSignatureEntity
import pl.detailing.crm.leads.similar.WorkNeed
import pl.detailing.crm.service.taxonomy.ServiceOperation
import pl.detailing.crm.service.taxonomy.ServicePart
import pl.detailing.crm.service.taxonomy.WorkAxisCompatibility
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Progi bramek — jako ARGUMENT czystej funkcji, nie stałe zaszyte w środku.
 *
 * To daje całą testowalność bez tabeli kalibracyjnej: test podmienia progi jednym
 * konstruktorem, a produkcja jedzie na wartościach domyślnych, zmienianych commitem
 * na podstawie golden setu. Kalibracja z danych wróci, gdy feedback przekroczy
 * ~200 wierszy z wypełnionym reason_code — wcześniej byłaby aparatem bayesowskim
 * zwracającym wartości domyślne przez pierwszy rok.
 */
data class GateThresholds(
    /** Dolna granica ilorazu ceny względem kotwicy. 850 zł przy 18 450 zł to 0,046 — poza pasmem. */
    val minPriceRatio: Double = 0.4,
    /** Górna granica ilorazu. */
    val maxPriceRatio: Double = 2.5,
    /** Pasmo ZWĘŻANE przy dużej kotwicy: przy 10 000+ zł rozrzut 2,5× to inna robota. */
    val tightMinPriceRatio: Double = 0.6,
    val tightMaxPriceRatio: Double = 1.8,
    /** Od tej kotwicy (grosze) obowiązuje pasmo zwężone. */
    val bigJobAnchorGross: Long = 1_000_000,
    /** Zlecenia starsze niż tyle miesięcy odpadają. */
    val maxAgeMonths: Long = 24,
    /** Starsze niż tyle miesięcy przechodzą z etykietą roku na karcie. */
    val softAgeMonths: Long = 12,
    /**
     * Minimalny udział KWOTOWY pasującej roboty w zleceniu. Zastępuje skupienie
     * liczone na licznościach tam, gdzie pieniądze pozycji są ostemplowane;
     * mianownikiem jest kwota, nie rozmiar zbioru.
     */
    val minValueFocus: Double = 0.25,
    /** Tyle compów potrzeba na pasmo (mniej → SINGLE / EVIDENCE_ONLY). */
    val minCompsForBand: Int = 3,
    /** band_max/band_min ponad to → za szeroko, żeby nazwać to ceną → EVIDENCE_ONLY. */
    val maxSpreadRatio: Double = 3.0,
    /** Rozrzut cen odniesienia, od którego uruchamia się weryfikator LLM. */
    val verifierSpreadTrigger: Double = 1.8
) {
    /** Dolna granica pasma dla danej kotwicy — zwężana przy dużej robocie. */
    fun minRatioFor(anchorGross: Long): Double =
        if (anchorGross > bigJobAnchorGross) tightMinPriceRatio else minPriceRatio
}

/** Klasa compa po bramkach (weryfikator może jeszcze zdegradować/odrzucić). */
enum class CompClass { DIRECT, ADJUSTED, REJECTED }

/**
 * Pełny werdykt bramek dla jednego kandydata — wszystko, co trafia do dziennika
 * decyzji, także (zwłaszcza!) dla odrzuconych.
 */
data class CompEvaluation(
    val visitId: UUID,
    val tier: MatchTier?,
    val compClass: CompClass,
    val rejectCode: String?,
    /** Iloraz ceny odniesienia do kotwicy; null gdy którejś z liczb brak. */
    val priceRatio: Double?,
    /** Udział kwotowy pasującej roboty w zleceniu; null gdy pieniądze nieostemplowane. */
    val valueFocus: Double?,
    /** Pokrycie z kraty (licznościowe) — do dziennika. */
    val valueCoverage: Double?,
    /**
     * Cena ODNIESIENIA compa: suma linii pasującej roboty, a gdy linie nieostemplowane —
     * kwota całego zlecenia. To ona wchodzi do pasma.
     */
    val referenceGross: Long?,
    /** Zlecenie z pasma 12–24 mies. — karta dostaje etykietę roku. */
    val agedLabel: Boolean,
    val happenedAt: Instant?
) {
    companion object {
        const val REJECT_CAR_MISMATCH = "CAR_MISMATCH"
        const val REJECT_NO_INTENT = "NO_INTENT"
        const val REJECT_NO_SIGNATURES = "NO_SIGNATURES"
        const val REJECT_OPERATION_MISMATCH = "OPERATION_MISMATCH"
        const val REJECT_SURFACE_MISMATCH = "SURFACE_MISMATCH"
        const val REJECT_WORK_MISMATCH = "WORK_MISMATCH"
        const val REJECT_SCALE_MISMATCH = "SCALE_MISMATCH"
        const val REJECT_VALUE_FOCUS = "VALUE_FOCUS"
        const val REJECT_TOO_OLD = "TOO_OLD"
        const val REJECT_VERIFIER = "VERIFIER_REJECTED"
    }
}

/**
 * Bramki dopuszczenia kotwicy cenowej — CZYSTA FUNKCJA, zero zależności, zero LLM.
 *
 * Zasada naczelna przebudowy w kodzie: kotwicę wolno pokazać tylko wtedy, gdy
 * różnicę między realizacją a zapytaniem da się wyrazić jako małą, policzalną
 * korektę na liczbach POCHODZĄCYCH Z BAZY. Miara niedostępna jest POMIJANA,
 * nigdy zastępowana domysłem — dlatego wiersz z totalGross = 0 (nieprzestemplowany)
 * i kotwica null (requireManualPrice bez historii) NIE uruchamiają bramki skali,
 * zamiast kasować wszystkich kandydatów.
 *
 * Kolejność bramek = kolejność kodów odrzucenia w dzienniku:
 *   auto → intencja → sygnatury → OSIE (rzemiosło/część) → krata → SKALA → SKUPIENIE → WIEK
 */
object AnchorGate {

    fun evaluate(
        candidate: VisitIndexStateEntity,
        signatures: List<VisitServiceSignatureEntity>,
        intent: LeadServiceIntent,
        leadBrandKey: String?,
        leadModelKey: String?,
        leadSegment: String?,
        now: Instant,
        thresholds: GateThresholds = GateThresholds()
    ): CompEvaluation {
        val sameModel = leadBrandKey != null && leadModelKey != null &&
            candidate.brandKey == leadBrandKey && candidate.modelKey == leadModelKey
        val sameSegment = !leadSegment.isNullOrBlank() && leadSegment != "UNKNOWN" &&
            candidate.sizeSegment == leadSegment
        if (!sameModel && !sameSegment) return rejected(candidate, CompEvaluation.REJECT_CAR_MISMATCH)

        if (intent.status != ServiceIntentStatus.MATCHED) {
            return rejected(candidate, CompEvaluation.REJECT_NO_INTENT)
        }
        if (signatures.isEmpty()) return rejected(candidate, CompEvaluation.REJECT_NO_SIGNATURES)

        // ── Bramki osi: rzemiosło i część auta ──────────────────────────────────
        // Dyskwalifikacja jest TWARDA (nigdy degradacja do „podobnej") i działa
        // wyłącznie na danych ZNANYCH po obu stronach — UNKNOWN pomija bramkę.
        val disqualification = axisDisqualification(signatures, intent.needs)

        val lattice = SimilarVisitMatcher.evaluate(
            candidate, signatures, intent, leadBrandKey, leadModelKey, leadSegment,
            axisDisqualified = disqualification.keys
        )

        if (lattice.tier == null) {
            // Które ramię zawiodło: jeśli bramki osi zdjęły pozycje, to one są
            // przyczyną — bez nich krata mogła przejść; inaczej zwykłe pokrycie.
            val code = when {
                disqualification.values.any { it == AxisConflict.OPERATION } -> CompEvaluation.REJECT_OPERATION_MISMATCH
                disqualification.values.any { it == AxisConflict.PART } -> CompEvaluation.REJECT_SURFACE_MISMATCH
                else -> CompEvaluation.REJECT_WORK_MISMATCH
            }
            return rejected(candidate, code, coverage = lattice.coverage)
        }

        // ── Pieniądze ───────────────────────────────────────────────────────────
        val matchedLineGross = signatures
            .filter { it.id in lattice.matchingSignatureIds }
            .sumOf { it.linePriceGross }
        val referenceGross = matchedLineGross.takeIf { it > 0 }
            ?: candidate.totalGross.takeIf { it > 0 }

        // ── Bramka skali ────────────────────────────────────────────────────────
        val anchor = intent.anchorGross
        val priceRatio = if (anchor != null && anchor > 0 && referenceGross != null) {
            referenceGross.toDouble() / anchor
        } else null

        if (priceRatio != null) {
            val big = anchor!! > thresholds.bigJobAnchorGross
            val min = if (big) thresholds.tightMinPriceRatio else thresholds.minPriceRatio
            val max = if (big) thresholds.tightMaxPriceRatio else thresholds.maxPriceRatio
            if (priceRatio < min || priceRatio > max) {
                return rejected(
                    candidate, CompEvaluation.REJECT_SCALE_MISMATCH,
                    tier = lattice.tier, priceRatio = priceRatio,
                    referenceGross = referenceGross, coverage = lattice.coverage
                )
            }
        }

        // ── Skupienie liczone na KWOTACH ────────────────────────────────────────
        // Mianownikiem jest kwota zlecenia, nie liczba pozycji: zlecenie
        // jednopozycyjne przestaje mieć skupienie 1.0 z samej definicji.
        val valueFocus = if (matchedLineGross > 0 && candidate.totalGross > 0) {
            matchedLineGross.toDouble() / candidate.totalGross
        } else null
        if (valueFocus != null && valueFocus < thresholds.minValueFocus) {
            return rejected(
                candidate, CompEvaluation.REJECT_VALUE_FOCUS,
                tier = lattice.tier, priceRatio = priceRatio,
                valueFocus = valueFocus, referenceGross = referenceGross,
                coverage = lattice.coverage
            )
        }

        // ── Wiek ────────────────────────────────────────────────────────────────
        val happenedAt = candidate.happenedAt
        var aged = false
        if (happenedAt != null) {
            val ageMonths = ChronoUnit.DAYS.between(happenedAt, now) / 30
            if (ageMonths > thresholds.maxAgeMonths) {
                return rejected(
                    candidate, CompEvaluation.REJECT_TOO_OLD,
                    tier = lattice.tier, priceRatio = priceRatio,
                    valueFocus = valueFocus, referenceGross = referenceGross,
                    coverage = lattice.coverage
                )
            }
            aged = ageMonths > thresholds.softAgeMonths
        }

        val compClass = when (lattice.tier) {
            MatchTier.SAME_MODEL_SAME_SERVICE, MatchTier.SAME_SEGMENT_SAME_SERVICE -> CompClass.DIRECT
            else -> CompClass.ADJUSTED
        }

        return CompEvaluation(
            visitId = candidate.visitId,
            tier = lattice.tier,
            compClass = compClass,
            rejectCode = null,
            priceRatio = priceRatio,
            valueFocus = valueFocus,
            valueCoverage = lattice.coverage,
            referenceGross = referenceGross,
            agedLabel = aged,
            happenedAt = happenedAt
        )
    }

    private enum class AxisConflict { OPERATION, PART }

    /**
     * Sygnatury, których osie JAWNIE kłócą się z każdą potrzebą leada.
     * Brak potrzeb albo UNKNOWN po którejś stronie = brak dyskwalifikacji —
     * o odrzuceniu może wtedy zdecydować wyłącznie bramka, która ma dane.
     */
    private fun axisDisqualification(
        signatures: List<VisitServiceSignatureEntity>,
        needs: List<WorkNeed>
    ): Map<UUID, AxisConflict> {
        if (needs.isEmpty()) return emptyMap()

        val result = mutableMapOf<UUID, AxisConflict>()
        for (signature in signatures) {
            val op = ServiceOperation.from(signature.operation)
            val part = ServicePart.from(signature.part)

            val operationOk = needs.any { WorkAxisCompatibility.operationsComparable(it.operation, op) }
            if (!operationOk) {
                result[signature.id] = AxisConflict.OPERATION
                continue
            }
            val partOk = needs.any { need ->
                WorkAxisCompatibility.operationsComparable(need.operation, op) &&
                    WorkAxisCompatibility.partsComparable(
                        if (op != ServiceOperation.UNKNOWN) op else need.operation,
                        need.part,
                        part
                    )
            }
            if (!partOk) result[signature.id] = AxisConflict.PART
        }
        return result
    }

    private fun rejected(
        candidate: VisitIndexStateEntity,
        code: String,
        tier: MatchTier? = null,
        priceRatio: Double? = null,
        valueFocus: Double? = null,
        referenceGross: Long? = null,
        coverage: Double? = null
    ) = CompEvaluation(
        visitId = candidate.visitId,
        tier = tier,
        compClass = CompClass.REJECTED,
        rejectCode = code,
        priceRatio = priceRatio,
        valueFocus = valueFocus,
        valueCoverage = coverage,
        referenceGross = referenceGross,
        agedLabel = false,
        happenedAt = candidate.happenedAt
    )
}
