package pl.detailing.crm.instagram.analytics

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.instagram.infrastructure.*
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.InstagramProfileStatus
import pl.detailing.crm.shared.StudioId
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.*

/**
 * Warstwa odczytu analityki v2. Czyta niemal wyłącznie z gotowych agregatów
 * (instagram_profile_stats_weekly) i lekkich tabel pomocniczych – bez przeliczania
 * pełnych snapshotów przy każdym żądaniu.
 */
@Service
class InstagramAnalyticsReadService(
    private val studioProfileRepository: StudioInstagramProfileRepository,
    private val profileRepository: InstagramProfileRepository,
    private val statsRepository: InstagramProfileStatsWeeklyRepository,
    private val metricsRepository: InstagramProfileMetricsSnapshotRepository,
    private val postRepository: InstagramPostSnapshotRepository,
    private val topicRepository: InstagramPostTopicRepository,
    private val reactionRepository: StudioInstagramPostReactionRepository,
    private val insightRepository: InstagramInsightRepository,
    @org.springframework.beans.factory.annotation.Value("\${instagram.daily-sync.cron:0 30 6 * * *}")
    private val dailySyncCron: String,
    @org.springframework.beans.factory.annotation.Value("\${instagram.sync.cron:0 0 3 * * SUN}")
    private val deepSyncCron: String
) {

    /** Najbliższe odpalenie crona wg zegara serwera (tak samo interpretuje go @Scheduled). */
    private fun nextFire(cron: String): Instant? = runCatching {
        org.springframework.scheduling.support.CronExpression.parse(cron)
            .next(java.time.ZonedDateTime.now())
            ?.toInstant()
    }.getOrNull()

    companion object {
        private val WARSAW = ZoneId.of("Europe/Warsaw")
        const val DEFAULT_WEEKS = 12

        /**
         * Minimalna liczba INNYCH profili, żeby mediana grupy była wiarygodna.
         * Poniżej progu benchmark = null i UI pokazuje tylko delty + CTA.
         */
        const val MIN_COMPARISON_GROUP = 3
    }

    /** Mediana wartości pozostałych profili (leave-one-out); null poniżej progu wiarygodności. */
    private fun groupMedian(values: List<Double?>): Double? {
        val present = values.filterNotNull()
        return if (present.size >= MIN_COMPARISON_GROUP) MetricsCalculator.median(present) else null
    }

    // ── Wspólny kontekst koszyka ──────────────────────────────────────────────

    private data class Basket(
        val links: List<StudioInstagramProfileEntity>,
        val profiles: Map<UUID, InstagramProfileEntity>,
        val selfLink: StudioInstagramProfileEntity?
    )

    private fun loadBasket(studioId: StudioId): Basket {
        val links = studioProfileRepository.findByStudioIdAndStatus(studioId.value, InstagramProfileStatus.ACTIVE)
        val profiles = profileRepository.findAllById(links.map { it.profileId }).associateBy { it.id }
        return Basket(links, profiles, links.firstOrNull { it.isSelf })
    }

    /** Metryki jednego profilu w oknie (obliczone z wierszy stats_weekly). */
    private data class ProfileWindowMetrics(
        val link: StudioInstagramProfileEntity,
        val profile: InstagramProfileEntity,
        val postsPerWeek: Double?,
        val prevPostsPerWeek: Double?,
        val erPct: Double?,
        val prevErPct: Double?,
        val followers: Double?,
        val prevFollowers: Double?,
        val regularityPct: Double,
        val activityIndex: Int,
        val prevActivityIndex: Int,
        val formatMix: FormatMixDto,
        val followerDelta30d: Int?,
        val lastActiveWeek: LocalDate?
    )

    private fun computeWindowMetrics(basket: Basket, weeks: Int): List<ProfileWindowMetrics> {
        if (basket.links.isEmpty()) return emptyList()

        val currentMonday = MetricsCalculator.currentWeekStart()
        val windowFrom = currentMonday.minusWeeks(weeks.toLong() - 1)
        val prevFrom = windowFrom.minusWeeks(weeks.toLong())

        val allStats = statsRepository.findByProfileIdInAndWeekStartGreaterThanEqual(
            basket.links.map { it.profileId }, prevFrom
        ).groupBy { it.profileId }

        return basket.links.mapNotNull { link ->
            val profile = basket.profiles[link.profileId] ?: return@mapNotNull null
            val stats = allStats[link.profileId] ?: emptyList()

            val current = stats.filter { it.weekStart >= windowFrom }
            val previous = stats.filter { it.weekStart < windowFrom }

            fun window(rows: List<InstagramProfileStatsWeeklyEntity>): Triple<Double?, Double?, Double?> {
                if (rows.isEmpty()) return Triple(null, null, null)
                val posts = rows.sumOf { it.postCount }
                val postsPerWeek = posts.toDouble() / weeks
                val followers = rows.sortedBy { it.weekStart }.lastOrNull { it.followerEnd != null }?.followerEnd
                val er = MetricsCalculator.erPct(
                    rows.sumOf { it.totalLikes.toLong() },
                    rows.sumOf { it.totalComments.toLong() },
                    posts,
                    followers
                )
                return Triple(postsPerWeek, er, followers?.toDouble())
            }

            val (postsPerWeek, er, followers) = window(current)
            val (prevPostsPerWeek, prevEr, prevFollowers) = window(previous)

            val regularity = MetricsCalculator.regularityPct(current)
            val prevRegularity = MetricsCalculator.regularityPct(previous)

            val growthPct = MetricsCalculator.deltaPct(followers, prevFollowers)
            val activityIndex = MetricsCalculator.activityIndex(postsPerWeek ?: 0.0, er, regularity, growthPct)
            val prevActivityIndex = MetricsCalculator.activityIndex(prevPostsPerWeek ?: 0.0, prevEr, prevRegularity, null)

            val totalPosts = current.sumOf { it.postCount }
            val formatMix = if (totalPosts > 0) FormatMixDto(
                photoPct = current.sumOf { it.photoCount } * 100.0 / totalPosts,
                reelsPct = current.sumOf { it.reelsCount } * 100.0 / totalPosts,
                carouselPct = current.sumOf { it.carouselCount } * 100.0 / totalPosts
            ) else FormatMixDto(0.0, 0.0, 0.0)

            // Delta obserwujących 30 dni – z wierszy tygodniowych (4 ostatnie tygodnie)
            val followerDelta30d = current
                .filter { it.weekStart >= currentMonday.minusWeeks(4) }
                .mapNotNull { it.followerDelta }
                .takeIf { it.isNotEmpty() }?.sum()

            ProfileWindowMetrics(
                link = link,
                profile = profile,
                postsPerWeek = postsPerWeek,
                prevPostsPerWeek = prevPostsPerWeek,
                erPct = er,
                prevErPct = prevEr,
                followers = followers,
                prevFollowers = prevFollowers,
                regularityPct = regularity,
                activityIndex = activityIndex,
                prevActivityIndex = prevActivityIndex,
                formatMix = formatMix,
                followerDelta30d = followerDelta30d,
                lastActiveWeek = stats.filter { it.postCount > 0 }.maxOfOrNull { it.weekStart }
            )
        }
    }

    private fun triple(value: Double?, previous: Double?, benchmark: Double?) = MetricTriple(
        value = value,
        previous = previous,
        deltaPct = MetricsCalculator.deltaPct(value, previous),
        benchmark = benchmark
    )

    // ── Overview ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun overview(studioId: StudioId, weeks: Int): OverviewResponse {
        val basket = loadBasket(studioId)
        val metrics = computeWindowMetrics(basket, weeks)

        val ranked = metrics.sortedWith(
            compareByDescending<ProfileWindowMetrics> { it.activityIndex }
                .thenByDescending { it.followers ?: 0.0 }
        )
        val self = ranked.firstOrNull { it.link.isSelf }

        // Benchmark liczony wyłącznie z konkurencji (leave-one-out) i tylko przy
        // wystarczającej grupie porównawczej – patrz MIN_COMPARISON_GROUP
        val competitors = metrics.filter { !it.link.isSelf }
        val erMedian = groupMedian(competitors.map { it.erPct })
        val ppwMedian = groupMedian(competitors.map { it.postsPerWeek })
        val aiMedian = groupMedian(competitors.map { it.activityIndex.toDouble() })

        val subject = self
        val erTriple = if (subject != null) triple(subject.erPct, subject.prevErPct, erMedian)
        else MetricTriple(erMedian, null, null, null)
        val ppwTriple = if (subject != null) triple(subject.postsPerWeek, subject.prevPostsPerWeek, ppwMedian)
        else MetricTriple(ppwMedian, null, null, null)
        val aiTriple = if (subject != null)
            triple(subject.activityIndex.toDouble(), subject.prevActivityIndex.toDouble(), aiMedian)
        else MetricTriple(aiMedian, null, null, null)

        val storefront = subject?.let {
            // Real last-post timestamp from post snapshots — the week-level approximation
            // could mark a freshly-posting profile as stale when aggregates lagged behind.
            val lastPostAt = postRepository.findFirstByProfileIdOrderByTakenAtDesc(it.profile.id)?.takenAt
            val score = MetricsCalculator.storefrontScore(it.profile, lastPostAt)
            StorefrontDto(score.score, score.gaps.map { g -> g.label })
        }

        return OverviewResponse(
            weeks = weeks,
            comparisonGroupSize = metrics.size,
            lastSyncAt = basket.profiles.values.mapNotNull { it.detailsLastSyncedAt }.maxOrNull(),
            nextDailySyncAt = nextFire(dailySyncCron),
            nextDeepSyncAt = nextFire(deepSyncCron),
            profilesCount = basket.links.size,
            hasSelf = self != null,
            selfUsername = self?.profile?.username,
            position = self?.let { PositionDto(rank = ranked.indexOf(it) + 1, total = ranked.size) },
            erPct = erTriple,
            postsPerWeek = ppwTriple,
            activityIndex = aiTriple,
            storefront = storefront,
            miniRanking = ranked.take(6).map {
                MiniRankRowDto(
                    studioProfileId = it.link.id.toString(),
                    profileId = it.profile.id.toString(),
                    username = it.profile.username,
                    isSelf = it.link.isSelf,
                    followers = it.followers?.toInt(),
                    followerDelta30d = it.followerDelta30d,
                    erPct = it.erPct,
                    postsPerWeek = it.postsPerWeek,
                    activityIndex = it.activityIndex
                )
            }
        )
    }

    // ── Benchmark ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun benchmark(studioId: StudioId, weeks: Int): BenchmarkResponse {
        val basket = loadBasket(studioId)
        val metrics = computeWindowMetrics(basket, weeks)
        val lastPostAtByProfile = postRepository
            .findLastPostAtByProfileIds(basket.links.map { it.profileId })
            .associate { it.profileId to it.lastPostAt }

        val rows = metrics
            .sortedWith(compareByDescending<ProfileWindowMetrics> { it.link.isSelf }
                .thenByDescending { it.activityIndex })
            .map { m ->
                // Leave-one-out: profil nigdy nie wchodzi do własnego benchmarku
                val others = metrics.filter { it.link.id != m.link.id }
                val storefront = MetricsCalculator.storefrontScore(m.profile, lastPostAtByProfile[m.profile.id])
                BenchmarkRowDto(
                    studioProfileId = m.link.id.toString(),
                    profileId = m.profile.id.toString(),
                    username = m.profile.username,
                    isSelf = m.link.isSelf,
                    apiError = m.profile.apiError,
                    followers = triple(m.followers, m.prevFollowers, groupMedian(others.map { it.followers })),
                    erPct = triple(m.erPct, m.prevErPct, groupMedian(others.map { it.erPct })),
                    postsPerWeek = triple(m.postsPerWeek, m.prevPostsPerWeek, groupMedian(others.map { it.postsPerWeek })),
                    regularityPct = m.regularityPct,
                    formatMix = m.formatMix,
                    activityIndex = triple(
                        m.activityIndex.toDouble(),
                        m.prevActivityIndex.toDouble(),
                        groupMedian(others.map { it.activityIndex.toDouble() })
                    ),
                    storefront = StorefrontDto(storefront.score, storefront.gaps.map { it.label })
                )
            }

        // Serie tygodniowe do wykresu aktywności
        val currentMonday = MetricsCalculator.currentWeekStart()
        val windowFrom = currentMonday.minusWeeks(weeks.toLong() - 1)
        val statsByProfile = statsRepository.findByProfileIdInAndWeekStartGreaterThanEqual(
            basket.links.map { it.profileId }, windowFrom
        ).groupBy { it.profileId }

        val weekly = (0 until weeks).reversed().map { offset ->
            val monday = currentMonday.minusWeeks(offset.toLong())
            WeeklyChartPointDto(
                weekStart = monday.toString(),
                values = basket.links.mapNotNull { link ->
                    val row = statsByProfile[link.profileId]?.firstOrNull { it.weekStart == monday }
                    link.profileId.toString() to WeeklyProfilePointDto(
                        posts = row?.postCount ?: 0,
                        engagement = (row?.totalLikes ?: 0) + (row?.totalComments ?: 0)
                    )
                }.toMap()
            )
        }

        // Serie dzienne obserwujących
        val followerHistory = metricsRepository.findByProfileIdInAndSnapshotDateAfterOrderBySnapshotDateAsc(
            basket.links.map { it.profileId }, windowFrom
        ).groupBy { it.profileId }

        val followers = basket.links.mapNotNull { link ->
            val profile = basket.profiles[link.profileId] ?: return@mapNotNull null
            FollowerSeriesDto(
                profileId = link.profileId.toString(),
                username = profile.username,
                isSelf = link.isSelf,
                observedSince = link.createdAt.atZone(ZoneOffset.UTC).toLocalDate().toString(),
                points = (followerHistory[link.profileId] ?: emptyList()).map {
                    FollowerPointDto(it.snapshotDate.toString(), it.followerCount)
                }
            )
        }

        // Adnotacje zdarzeń na osi czasu (insighty z okna)
        val annotations = insightRepository.findByStudioIdAndCreatedAtAfterOrderByCreatedAtDesc(
            studioId.value, windowFrom.atStartOfDay(ZoneOffset.UTC).toInstant()
        ).filter { it.type in setOf("PROMO_DETECTED", "CONTEST_DETECTED", "VIRAL_POST", "FOLLOWER_SPIKE") }
            .map {
                ChartAnnotationDto(
                    date = it.createdAt.atZone(ZoneOffset.UTC).toLocalDate().toString(),
                    profileId = it.profileId?.toString(),
                    type = it.type,
                    title = it.title
                )
            }

        return BenchmarkResponse(weeks, metrics.size, rows, weekly, followers, annotations)
    }

    // ── Treści ────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun content(
        studioId: StudioId,
        weeks: Int,
        sort: String,
        topic: String?,
        format: String?,
        profileId: String?,
        promoOnly: Boolean,
        page: Int,
        pageSize: Int
    ): ContentResponse {
        val basket = loadBasket(studioId)
        if (basket.links.isEmpty()) return ContentResponse(emptyList(), page, pageSize, 0, emptyList())

        val cutoff = Instant.now().minusSeconds(weeks.toLong() * 7 * 24 * 3600)
        val posts = postRepository.findByProfileIdInAndTakenAtAfter(basket.links.map { it.profileId }, cutoff)
        val topics = topicRepository.findByPostIdIn(posts.map { it.id }).associateBy { it.postId }
        val reactions = reactionRepository.findByStudioIdAndPostIdIn(studioId.value, posts.map { it.id })
            .associateBy { it.postId }
        val selfIds = basket.links.filter { it.isSelf }.map { it.profileId }.toSet()

        val allItems = posts.mapNotNull { post ->
            val profile = basket.profiles[post.profileId] ?: return@mapNotNull null
            val topicEntity = topics[post.id]
            val topicName = topicEntity?.topic ?: InstagramPostTopic.INNE.name
            val engagement = post.likeCount + post.commentCount
            ContentItemDto(
                postId = post.id.toString(),
                profileId = post.profileId.toString(),
                username = profile.username,
                isSelf = post.profileId in selfIds,
                takenAt = post.takenAt,
                permalink = "https://www.instagram.com/p/${post.postCode}/",
                caption = post.caption,
                likeCount = post.likeCount,
                commentCount = post.commentCount,
                viewCount = post.viewCount,
                format = MetricsCalculator.formatBucket(post.productType),
                topic = topicName,
                topicLabel = InstagramPostTopic.labelFor(topicName),
                isPromo = topicEntity?.isPromo ?: false,
                isContest = topicEntity?.isContest ?: false,
                erPct = profile.followerCount?.takeIf { it > 0 }?.let { engagement.toDouble() / it * 100.0 },
                engagement = engagement,
                reaction = reactions[post.id]?.reaction?.name
            )
        }

        val topicOptions = allItems.groupBy { it.topic }
            .map { (value, items) -> TopicOptionDto(value, InstagramPostTopic.labelFor(value), items.size) }
            .sortedByDescending { it.count }

        val filtered = allItems.asSequence()
            .filter { topic == null || it.topic == topic }
            .filter { format == null || it.format == format }
            .filter { profileId == null || it.profileId == profileId }
            .filter { !promoOnly || it.isPromo || it.isContest }
            .toList()

        val sorted = when (sort) {
            "date" -> filtered.sortedByDescending { it.takenAt }
            "likes" -> filtered.sortedByDescending { it.likeCount }
            else -> filtered.sortedWith(
                compareByDescending<ContentItemDto> { it.erPct ?: -1.0 }.thenByDescending { it.engagement }
            )
        }

        val fromIndex = (page * pageSize).coerceAtMost(sorted.size)
        val toIndex = (fromIndex + pageSize).coerceAtMost(sorted.size)

        return ContentResponse(
            items = sorted.subList(fromIndex, toIndex),
            page = page,
            pageSize = pageSize,
            totalItems = sorted.size,
            topics = topicOptions
        )
    }

    @Transactional(readOnly = true)
    fun heatmap(studioId: StudioId, weeks: Int): HeatmapResponse {
        val basket = loadBasket(studioId)
        if (basket.links.isEmpty()) return HeatmapResponse(emptyList(), null, null)

        val cutoff = Instant.now().minusSeconds(weeks.toLong() * 7 * 24 * 3600)
        val posts = postRepository.findByProfileIdInAndTakenAtAfter(basket.links.map { it.profileId }, cutoff)

        fun daypart(hour: Int): Int = when (hour) {
            in 6..10 -> 0
            in 11..15 -> 1
            in 16..20 -> 2
            else -> 3
        }

        val cells = posts.groupBy {
            val local = it.takenAt.atZone(WARSAW)
            local.dayOfWeek.value to daypart(local.hour)
        }.map { (key, cellPosts) ->
            HeatmapCellDto(
                dayOfWeek = key.first,
                daypart = key.second,
                posts = cellPosts.size,
                avgEngagement = cellPosts.sumOf { it.likeCount + it.commentCount }.toDouble() / cellPosts.size
            )
        }

        val best = cells.filter { it.posts >= 2 }.maxByOrNull { it.avgEngagement }

        return HeatmapResponse(cells, best?.dayOfWeek, best?.daypart)
    }

    @Transactional(readOnly = true)
    fun hashtags(studioId: StudioId, weeks: Int): HashtagsResponse {
        val basket = loadBasket(studioId)
        if (basket.links.isEmpty()) return HashtagsResponse(emptyList())

        val cutoff = Instant.now().minusSeconds(weeks.toLong() * 7 * 24 * 3600)
        val posts = postRepository.findByProfileIdInAndTakenAtAfter(basket.links.map { it.profileId }, cutoff)

        data class TagAgg(var uses: Int = 0, var engagement: Long = 0, val profiles: MutableSet<UUID> = mutableSetOf())

        val aggregates = mutableMapOf<String, TagAgg>()
        posts.forEach { post ->
            post.hashtags?.split(",")?.filter { it.isNotBlank() }?.distinct()?.forEach { tag ->
                val agg = aggregates.getOrPut(tag) { TagAgg() }
                agg.uses++
                agg.engagement += (post.likeCount + post.commentCount).toLong()
                agg.profiles += post.profileId
            }
        }

        val top = aggregates.entries
            .filter { it.value.uses >= 2 }
            .sortedByDescending { it.value.uses }
            .take(15)
            .map { (tag, agg) ->
                HashtagStatDto(
                    tag = tag,
                    uses = agg.uses,
                    profilesCount = agg.profiles.size,
                    avgEngagement = agg.engagement.toDouble() / agg.uses
                )
            }

        return HashtagsResponse(top)
    }

    /*
     * Insighty nie mają już własnego ekranu — „Co wymaga Twojej uwagi" powtarzało
     * to samo, co digest tygodnia, tylko innymi progami. InsightEngine zostaje
     * WYŁĄCZNIE jako źródło znaczników na wykresach w „Porównaniu" (adnotacje przy
     * słupkach i panel wyjaśnienia tygodnia) — tam niesie coś, czego digest nie ma:
     * pozycję zdarzenia na osi czasu.
     */

    // ── pomocnicze ────────────────────────────────────────────────────────────


    // ── Wyjaśnienie tygodnia (klik w słupek "Przyrost obserwujących") ─────────

    @Transactional(readOnly = true)
    fun weekDetail(studioId: StudioId, profileIdStr: String, weekStartStr: String): WeekDetailResponse {
        val basket = loadBasket(studioId)
        val profileId = UUID.fromString(profileIdStr)
        val link = basket.links.firstOrNull { it.profileId == profileId }
            ?: throw EntityNotFoundException("Profil nie należy do obserwowanego koszyka")
        val profile = basket.profiles[profileId]
            ?: throw EntityNotFoundException("Profil nie został znaleziony")

        // Normalizacja do poniedziałku ISO — front może kliknąć słupek dzienny.
        val requested = LocalDate.parse(weekStartStr)
        val weekStart = requested.minusDays(((requested.dayOfWeek.value + 6) % 7).toLong())
        val weekEnd = weekStart.plusDays(7)
        val weekStartInstant = weekStart.atStartOfDay(ZoneOffset.UTC).toInstant()
        val weekEndInstant = weekEnd.atStartOfDay(ZoneOffset.UTC).toInstant()

        // Delta obserwujących w tygodniu — z dziennych snapshotów.
        val snapshots = metricsRepository.findByProfileIdAndSnapshotDateAfterOrderBySnapshotDateAsc(
            profileId, weekStart.minusDays(1)
        ).filter { it.snapshotDate < weekEnd && it.followerCount != null }
        val followerDelta = if (snapshots.size >= 2) {
            snapshots.last().followerCount!! - snapshots.first().followerCount!!
        } else null

        // Posty opublikowane w tym tygodniu.
        val weekPosts = postRepository.findByProfileIdAndTakenAtAfter(profileId, weekStartInstant)
            .filter { it.takenAt < weekEndInstant }
            .sortedByDescending { it.takenAt }

        // Kontekst: mediany profilu z 8 tygodni — do wykrycia "wyraźnego skoku".
        val contextPosts = postRepository.findByProfileIdAndTakenAtAfter(
            profileId, weekStartInstant.minus(56, java.time.temporal.ChronoUnit.DAYS)
        )
        val medianEngagement = MetricsCalculator.median(
            contextPosts.map { (it.likeCount + it.commentCount).toDouble() }
        )
        val medianViews = MetricsCalculator.median(
            contextPosts.mapNotNull { it.viewCount?.toDouble() }
        )

        // Insighty (promocja/konkurs/viral/spike) przypisane do tego profilu i tygodnia.
        val weekInsights = insightRepository.findByStudioIdAndCreatedAtAfterOrderByCreatedAtDesc(
            studioId.value, weekStartInstant
        ).filter {
            it.profileId == profileId &&
                it.createdAt < weekEndInstant.plus(7, java.time.temporal.ChronoUnit.DAYS) &&
                it.type in setOf("PROMO_DETECTED", "CONTEST_DETECTED", "VIRAL_POST", "FOLLOWER_SPIKE")
        }.map { WeekDetailInsightDto(type = it.type, title = it.title) }

        return WeekDetailResponse(
            profileId = profileIdStr,
            username = profile.username,
            weekStart = weekStart.toString(),
            followerDelta = followerDelta,
            medianEngagement = medianEngagement,
            medianViews = medianViews,
            posts = weekPosts.map { post ->
                WeekDetailPostDto(
                    postId = post.id.toString(),
                    permalink = "https://www.instagram.com/p/${post.postCode}/",
                    takenAt = post.takenAt,
                    format = MetricsCalculator.formatBucket(post.productType),
                    caption = post.caption?.take(120),
                    likeCount = post.likeCount,
                    commentCount = post.commentCount,
                    viewCount = post.viewCount,
                    engagement = post.likeCount + post.commentCount
                )
            },
            insights = weekInsights
        )
    }
}
