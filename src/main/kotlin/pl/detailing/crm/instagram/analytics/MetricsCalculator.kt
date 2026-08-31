package pl.detailing.crm.instagram.analytics

import pl.detailing.crm.instagram.infrastructure.InstagramProfileEntity
import pl.detailing.crm.instagram.infrastructure.InstagramProfileStatsWeeklyEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Czyste funkcje metryk – bez zależności od Springa, łatwe do testowania.
 * Formuły są jawne i proste: wynik musi być wytłumaczalny nietechnicznemu
 * właścicielowi studia jednym zdaniem.
 */
object MetricsCalculator {

    /** Poniedziałek tygodnia ISO (UTC) dla podanego momentu. */
    fun weekStart(instant: Instant): LocalDate =
        instant.atZone(ZoneOffset.UTC).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun currentWeekStart(): LocalDate =
        LocalDate.now(ZoneOffset.UTC).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /**
     * Zaangażowanie w % obserwujących: średnia (lajki+komentarze) na post / followers × 100.
     * Komunikowane w UI jako "na 100 obserwujących" – bez żargonu "ER".
     */
    fun erPct(totalLikes: Long, totalComments: Long, postCount: Int, followers: Int?): Double? {
        if (postCount <= 0 || followers == null || followers <= 0) return null
        val avgEngagement = (totalLikes + totalComments).toDouble() / postCount
        return avgEngagement / followers * 100.0
    }

    /**
     * Regularność publikacji: odsetek tygodni z przynajmniej jednym postem w oknie.
     * 100% = "publikuje co tydzień".
     */
    fun regularityPct(weeklyStats: List<InstagramProfileStatsWeeklyEntity>): Double {
        if (weeklyStats.isEmpty()) return 0.0
        val activeWeeks = weeklyStats.count { it.postCount > 0 }
        return activeWeeks.toDouble() / weeklyStats.size * 100.0
    }

    /**
     * Indeks aktywności marketingowej 0–100. Kompozyt czterech składników:
     * - tempo publikacji (40 pkt przy ≥3 postach/tydz.),
     * - zaangażowanie (30 pkt przy ER ≥ 5%),
     * - regularność (15 pkt przy publikacji w każdym tygodniu),
     * - wzrost obserwujących (15 pkt przy ≥ +2% w oknie).
     */
    fun activityIndex(
        postsPerWeek: Double,
        erPct: Double?,
        regularityPct: Double,
        followerGrowthPct: Double?
    ): Int {
        val pace = min(postsPerWeek / 3.0, 1.0) * 40.0
        val engagement = min((erPct ?: 0.0) / 5.0, 1.0) * 30.0
        val regularity = regularityPct / 100.0 * 15.0
        val growth = min(((followerGrowthPct ?: 0.0).coerceAtLeast(0.0)) / 2.0, 1.0) * 15.0
        return (pace + engagement + regularity + growth).roundToInt().coerceIn(0, 100)
    }

    /** Brak w profilu, z wagą punktową i przyjaznym opisem "co poprawić". */
    data class StorefrontGap(val points: Int, val label: String)

    data class StorefrontScore(val score: Int, val gaps: List<StorefrontGap>)

    /**
     * "Wizytówka profilu" 0–100 – jak kompletnie profil działa jako witryna firmy.
     * Zastępuje dawną macierz checkmarków jedną liczbą + listą braków.
     */
    fun storefrontScore(profile: InstagramProfileEntity, lastPostAt: Instant?): StorefrontScore {
        val gaps = mutableListOf<StorefrontGap>()
        var score = 100

        fun check(condition: Boolean, points: Int, gapLabel: String) {
            if (!condition) {
                score -= points
                gaps += StorefrontGap(points, gapLabel)
            }
        }

        check(profile.externalUrl != null, 20, "Dodaj link do strony lub rezerwacji w bio")
        check(profile.hasContactData, 20, "Uzupełnij publiczny telefon lub e-mail")
        check(profile.isBusiness || profile.accountType == 3, 15, "Przełącz konto na firmowe (profesjonalne)")
        check(profile.category != null, 10, "Ustaw kategorię działalności (np. usługi motoryzacyjne)")
        check((profile.biography?.length ?: 0) >= 50, 15, "Rozbuduj opis w bio – napisz, czym się zajmujesz i gdzie")
        val activeRecently = lastPostAt != null &&
            lastPostAt.isAfter(Instant.now().minusSeconds(14L * 24 * 3600))
        check(activeRecently, 20, "Opublikuj coś – ostatni post jest starszy niż 2 tygodnie")

        return StorefrontScore(score.coerceIn(0, 100), gaps)
    }

    /** Mediana; null dla pustej listy. */
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }

    /**
     * Percentyl metodą liniowej interpolacji (typ 7, ten sam co w R i NumPy).
     *
     * Potrzebny wszędzie tam, gdzie średnia kłamie przez pojedyncze wyskoki: jeden post
     * z oznaczonym celebrytą albo wypromowany płatnie potrafi mieć zasięg o dwa rzędy
     * wielkości większy od reszty i sam z siebie przesądzić o wyniku całej analizy.
     *
     * @param fraction 0.0–1.0 (0.9 = percentyl 90.)
     */
    fun percentile(values: List<Double>, fraction: Double): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted.first()

        val position = fraction.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val lower = kotlin.math.floor(position).toInt()
        val upper = kotlin.math.ceil(position).toInt()
        if (lower == upper) return sorted[lower]

        val weight = position - lower
        return sorted[lower] * (1 - weight) + sorted[upper] * weight
    }

    /** Zmiana procentowa; null gdy brak punktu odniesienia. */
    fun deltaPct(current: Double?, previous: Double?): Double? {
        if (current == null || previous == null || previous == 0.0) return null
        return (current - previous) / previous * 100.0
    }

    /**
     * Bucket formatu treści dla UI: REELS (clips/video), CAROUSEL, PHOTO.
     * `productType` bywa null dla starych rekordów – traktujemy jako PHOTO.
     */
    fun formatBucket(productType: String?): String = when (productType) {
        "clips", "video" -> "REELS"
        "carousel_container" -> "CAROUSEL"
        else -> "PHOTO"
    }
}
