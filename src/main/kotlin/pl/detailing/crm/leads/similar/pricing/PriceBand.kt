package pl.detailing.crm.leads.similar.pricing

/**
 * Pasmo cenowe z zaakceptowanych compów — CZYSTA FUNKCJA.
 *
 * Produktem sekcji nie jest lista wizyt, tylko odpowiedź na pytanie „ile bierzemy
 * za taką robotę": przedział z medianą, liczbą realizacji i uczciwą flagą
 * jednostronności. Pasmo jest niezmiennicze na kolejność compów — dlatego żaden
 * reranker nie jest tu potrzebny: weryfikator ORZEKA, kto wchodzi, a nie szereguje.
 */
object PriceBand {

    data class Band(
        val min: Long,
        val median: Long,
        val max: Long,
        val sampleSize: Int,
        /**
         * Wszystkie compsy leżą po tej samej stronie kotwicy, a mediana jest od niej
         * wyraźnie niższa — zestaw z przypadku PPF (same mniejsze realizacje) był
         * podręcznikowo jednostronny i pokazany jako „przedział" kłamałby.
         */
        val oneSided: Boolean,
        /** max/min — powyżej progu przedział jest za szeroki, żeby nazwać go ceną. */
        val spreadRatio: Double,
        /** (mediana − kotwica) / kotwica, gdy kotwica znana — rozbieżność katalog↔historia. */
        val divergence: Double?
    )

    fun of(amounts: List<Long>, anchorGross: Long?): Band? {
        val positive = amounts.filter { it > 0 }.sorted()
        if (positive.isEmpty()) return null

        val median = median(positive)
        val min = positive.first()
        val max = positive.last()
        val spread = if (min > 0) max.toDouble() / min else Double.MAX_VALUE

        val oneSided = anchorGross != null && anchorGross > 0 &&
            (positive.all { it < anchorGross } || positive.all { it > anchorGross }) &&
            median < anchorGross * ONE_SIDED_MEDIAN_SHARE

        val divergence = anchorGross?.takeIf { it > 0 }
            ?.let { (median - it).toDouble() / it }

        return Band(
            min = min,
            median = median,
            max = max,
            sampleSize = positive.size,
            oneSided = oneSided,
            spreadRatio = spread,
            divergence = divergence
        )
    }

    private fun median(sorted: List<Long>): Long {
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[middle]
        else (sorted[middle - 1] + sorted[middle]) / 2
    }

    /** Mediana poniżej 60% kotwicy przy zestawie jednostronnym = etykieta zamiast przedziału. */
    private const val ONE_SIDED_MEDIAN_SHARE = 0.6
}

/** Werdykt sekcji cenowej — co i jak pokazujemy. */
enum class AnchorVerdict {
    /** ≥3 porównywalne realizacje → przedział z medianą. */
    BAND,

    /** Dokładnie jedna → jedna realizacja, bez udawania przedziału. */
    SINGLE,

    /** 2 realizacje albo przedział za szeroki → dowód rzemiosła, nie dowód ceny. */
    EVIDENCE_ONLY,

    /** Nic uczciwego do pokazania — nazwane milczenie z kodem i CTA. */
    ABSTAIN
}

/**
 * Polityka abstencji — CZYSTA FUNKCJA rozstrzygająca werdykt sekcji.
 *
 * Kody startowe są CZTERY (plus NEEDS_INSPECTION niesione przez intencję) i tyle
 * mają zostać, dopóki dziennik nie pokaże, że któryś bucket przekracza 20% udziału —
 * taksonomii komunikatów nie projektuje się przed rozkładem realnych przypadków.
 */
object AbstentionPolicy {

    const val CODE_NO_VEHICLE = "NO_VEHICLE"
    const val CODE_NOT_IN_CATALOG = "NOT_IN_CATALOG"
    const val CODE_NEEDS_INSPECTION = "NEEDS_INSPECTION"
    const val CODE_NO_COMPARABLE = "NO_COMPARABLE"
    const val CODE_ANALYSIS_FAILED = "ANALYSIS_FAILED"

    data class Outcome(val verdict: AnchorVerdict, val abstentionCode: String?)

    fun decide(acceptedCount: Int, band: PriceBand.Band?, thresholds: GateThresholds): Outcome = when {
        acceptedCount == 0 || band == null ->
            Outcome(AnchorVerdict.ABSTAIN, CODE_NO_COMPARABLE)

        acceptedCount == 1 ->
            Outcome(AnchorVerdict.SINGLE, null)

        acceptedCount < thresholds.minCompsForBand ->
            Outcome(AnchorVerdict.EVIDENCE_ONLY, null)

        band.spreadRatio > thresholds.maxSpreadRatio ->
            // Od 1 200 do 6 400 zł to nie jest cena, tylko rozrzut — pokazujemy
            // realizacje jako dowód rzemiosła, bez liczby udającej przedział.
            Outcome(AnchorVerdict.EVIDENCE_ONLY, null)

        else -> Outcome(AnchorVerdict.BAND, null)
    }
}
