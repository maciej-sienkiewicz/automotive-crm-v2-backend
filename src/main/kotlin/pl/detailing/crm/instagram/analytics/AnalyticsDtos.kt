package pl.detailing.crm.instagram.analytics

import java.time.Instant

/**
 * Trójka metryki – reguła kontraktu API v2: żadna liczba nie idzie do UI bez kontekstu.
 * value    – wartość w bieżącym oknie,
 * previous – wartość w poprzednim oknie tej samej długości,
 * deltaPct – zmiana % vs previous (null gdy brak odniesienia),
 * benchmark – mediana wszystkich obserwowanych profili w bieżącym oknie.
 */
data class MetricTriple(
    val value: Double?,
    val previous: Double?,
    val deltaPct: Double?,
    val benchmark: Double?
)


data class StorefrontDto(
    val score: Int,
    val gaps: List<String>
)

// ── Overview ──────────────────────────────────────────────────────────────────

data class PositionDto(val rank: Int, val total: Int)

data class MiniRankRowDto(
    val studioProfileId: String,
    val profileId: String,
    val username: String,
    val isSelf: Boolean,
    val followers: Int?,
    val followerDelta30d: Int?,
    val erPct: Double?,
    val postsPerWeek: Double?,
    val activityIndex: Int
)

data class OverviewResponse(
    val weeks: Int,
    /** Liczebność obserwowanej grupy – porównania dotyczą wyłącznie tej grupy. */
    val comparisonGroupSize: Int,
    val lastSyncAt: Instant?,
    /** Najbliższa lekka synchronizacja (dzienna: metryki profili + najnowsze posty). */
    val nextDailySyncAt: Instant?,
    /** Najbliższa pełna synchronizacja (tygodniowa: głęboka historia + insighty + raport). */
    val nextDeepSyncAt: Instant?,
    val profilesCount: Int,
    val hasSelf: Boolean,
    val selfUsername: String?,
    /** Pozycja studia w rankingu indeksu aktywności; null gdy brak profilu "Ty". */
    val position: PositionDto?,
    /** Metryki "Ty" (gdy hasSelf) lub mediana koszyka (gdy brak). */
    val erPct: MetricTriple,
    val postsPerWeek: MetricTriple,
    val activityIndex: MetricTriple,
    val storefront: StorefrontDto?,
    val miniRanking: List<MiniRankRowDto>
)

// ── Benchmark (Porównanie) ────────────────────────────────────────────────────

data class FormatMixDto(
    val photoPct: Double,
    val reelsPct: Double,
    val carouselPct: Double
)

data class BenchmarkRowDto(
    val studioProfileId: String,
    val profileId: String,
    val username: String,
    val isSelf: Boolean,
    val apiError: Boolean,
    val followers: MetricTriple,
    val erPct: MetricTriple,
    val postsPerWeek: MetricTriple,
    val regularityPct: Double,
    val formatMix: FormatMixDto,
    val activityIndex: MetricTriple,
    val storefront: StorefrontDto
)

data class WeeklyProfilePointDto(val posts: Int, val engagement: Int)

data class WeeklyChartPointDto(
    val weekStart: String,
    /** klucz = profileId */
    val values: Map<String, WeeklyProfilePointDto>
)

data class FollowerPointDto(val date: String, val count: Int?)

data class FollowerSeriesDto(
    val profileId: String,
    val username: String,
    val isSelf: Boolean,
    /**
     * Dzień, od którego studio obserwuje ten profil (data dodania). Wcześniejszej historii
     * obserwujących nie da się odtworzyć — Instagram nie udostępnia archiwalnych stanów licznika.
     * Wykres używa tego do wyznaczenia wspólnego, uczciwego okna porównania.
     */
    val observedSince: String,
    val points: List<FollowerPointDto>
)

data class ChartAnnotationDto(
    val date: String,
    val profileId: String?,
    val type: String,
    val title: String
)

data class BenchmarkResponse(
    val weeks: Int,
    /** Liczebność obserwowanej grupy – porównania dotyczą wyłącznie tej grupy. */
    val comparisonGroupSize: Int,
    val rows: List<BenchmarkRowDto>,
    val weekly: List<WeeklyChartPointDto>,
    val followers: List<FollowerSeriesDto>,
    val annotations: List<ChartAnnotationDto>
)

// ── Wyjaśnienie tygodnia (klik w słupek przyrostu obserwujących) ─────────────

data class WeekDetailPostDto(
    val postId: String,
    val permalink: String,
    val takenAt: Instant,
    /** REELS | CAROUSEL | PHOTO */
    val format: String,
    val caption: String?,
    val likeCount: Int,
    val commentCount: Int,
    val viewCount: Long?,
    val engagement: Int
)

data class WeekDetailInsightDto(val type: String, val title: String)

data class WeekDetailResponse(
    val profileId: String,
    val username: String,
    val weekStart: String,
    val followerDelta: Int?,
    /** Mediana reakcji (lajki+komentarze) posta tego profilu z 8 tygodni — kontekst dla "skoku". */
    val medianEngagement: Double?,
    /** Mediana wyświetleń (tylko posty z licznikiem) z 8 tygodni. */
    val medianViews: Double?,
    val posts: List<WeekDetailPostDto>,
    val insights: List<WeekDetailInsightDto>
)

// ── Treści ────────────────────────────────────────────────────────────────────

data class ContentItemDto(
    val postId: String,
    val profileId: String,
    val username: String,
    val isSelf: Boolean,
    val takenAt: Instant,
    val permalink: String,
    val caption: String?,
    val likeCount: Int,
    val commentCount: Int,
    val viewCount: Long?,
    /** PHOTO | REELS | CAROUSEL */
    val format: String,
    val topic: String,
    val topicLabel: String,
    val isPromo: Boolean,
    val isContest: Boolean,
    /** Zaangażowanie posta w % obserwujących profilu; null gdy brak danych. */
    val erPct: Double?,
    val engagement: Int,
    /** LIKED | DISLIKED | null – ocena studia (trenuje generator AI). */
    val reaction: String?
)

data class TopicOptionDto(val value: String, val label: String, val count: Int)

data class ContentResponse(
    val items: List<ContentItemDto>,
    val page: Int,
    val pageSize: Int,
    val totalItems: Int,
    val topics: List<TopicOptionDto>
)

data class HeatmapCellDto(
    /** 1 = poniedziałek … 7 = niedziela (ISO). */
    val dayOfWeek: Int,
    /** 0: 6–11, 1: 11–16, 2: 16–21, 3: 21–6. */
    val daypart: Int,
    val posts: Int,
    val avgEngagement: Double
)

data class HeatmapResponse(
    val cells: List<HeatmapCellDto>,
    val bestDayOfWeek: Int?,
    val bestDaypart: Int?
)

data class HashtagStatDto(
    val tag: String,
    val uses: Int,
    val profilesCount: Int,
    val avgEngagement: Double
)

data class HashtagsResponse(val hashtags: List<HashtagStatDto>)

