package pl.detailing.crm.instagram.analytics

import org.springframework.data.domain.PageRequest
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
    private val insightRepository: InstagramInsightRepository
) {

    companion object {
        private val WARSAW = ZoneId.of("Europe/Warsaw")
        const val DEFAULT_WEEKS = 12
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

        val erMedian = MetricsCalculator.median(metrics.mapNotNull { it.erPct })
        val ppwMedian = MetricsCalculator.median(metrics.mapNotNull { it.postsPerWeek })
        val aiMedian = MetricsCalculator.median(metrics.map { it.activityIndex.toDouble() })

        val subject = self
        val erTriple = if (subject != null) triple(subject.erPct, subject.prevErPct, erMedian)
        else MetricTriple(erMedian, null, null, null)
        val ppwTriple = if (subject != null) triple(subject.postsPerWeek, subject.prevPostsPerWeek, ppwMedian)
        else MetricTriple(ppwMedian, null, null, null)
        val aiTriple = if (subject != null)
            triple(subject.activityIndex.toDouble(), subject.prevActivityIndex.toDouble(), aiMedian)
        else MetricTriple(aiMedian, null, null, null)

        val storefront = subject?.let {
            val score = MetricsCalculator.storefrontScore(it.profile, approxLastPost(it.lastActiveWeek))
            StorefrontDto(score.score, score.gaps.map { g -> g.label })
        }

        val insights = insightRepository
            .findByStudioIdAndStatusOrderByCreatedAtDesc(studioId.value, "NEW", PageRequest.of(0, 5))
            .map { it.toDto(basket) }

        return OverviewResponse(
            weeks = weeks,
            lastSyncAt = basket.profiles.values.mapNotNull { it.detailsLastSyncedAt }.maxOrNull(),
            profilesCount = basket.links.size,
            hasSelf = self != null,
            selfUsername = self?.profile?.username,
            position = self?.let { PositionDto(rank = ranked.indexOf(it) + 1, total = ranked.size) },
            erPct = erTriple,
            postsPerWeek = ppwTriple,
            activityIndex = aiTriple,
            storefront = storefront,
            insights = insights,
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

        val erMedian = MetricsCalculator.median(metrics.mapNotNull { it.erPct })
        val ppwMedian = MetricsCalculator.median(metrics.mapNotNull { it.postsPerWeek })
        val followersMedian = MetricsCalculator.median(metrics.mapNotNull { it.followers })
        val aiMedian = MetricsCalculator.median(metrics.map { it.activityIndex.toDouble() })

        val rows = metrics
            .sortedWith(compareByDescending<ProfileWindowMetrics> { it.link.isSelf }
                .thenByDescending { it.activityIndex })
            .map { m ->
                val storefront = MetricsCalculator.storefrontScore(m.profile, approxLastPost(m.lastActiveWeek))
                BenchmarkRowDto(
                    studioProfileId = m.link.id.toString(),
                    profileId = m.profile.id.toString(),
                    username = m.profile.username,
                    isSelf = m.link.isSelf,
                    apiError = m.profile.apiError,
                    followers = triple(m.followers, m.prevFollowers, followersMedian),
                    erPct = triple(m.erPct, m.prevErPct, erMedian),
                    postsPerWeek = triple(m.postsPerWeek, m.prevPostsPerWeek, ppwMedian),
                    regularityPct = m.regularityPct,
                    formatMix = m.formatMix,
                    activityIndex = triple(m.activityIndex.toDouble(), m.prevActivityIndex.toDouble(), aiMedian),
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

        return BenchmarkResponse(weeks, rows, weekly, followers, annotations)
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

    // ── Insighty ──────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    fun listInsights(studioId: StudioId, status: String?, limit: Int): InsightsListResponse {
        val basket = loadBasket(studioId)
        val page = PageRequest.of(0, limit.coerceIn(1, 100))
        val insights = if (status != null) {
            insightRepository.findByStudioIdAndStatusOrderByCreatedAtDesc(studioId.value, status, page)
        } else {
            insightRepository.findByStudioIdOrderByCreatedAtDesc(studioId.value, page)
        }
        return InsightsListResponse(insights.map { it.toDto(basket) })
    }

    @Transactional
    fun dismissInsight(studioId: StudioId, insightId: UUID) {
        val insight = insightRepository.findByStudioIdAndId(studioId.value, insightId)
            ?: throw EntityNotFoundException("Insight o id=$insightId nie istnieje w tym studio.")
        insight.status = "DISMISSED"
        insight.updatedAt = Instant.now()
        insightRepository.save(insight)
    }

    // ── pomocnicze ────────────────────────────────────────────────────────────

    private fun InstagramInsightEntity.toDto(basket: Basket): InsightDto {
        val username = profileId?.let { basket.profiles[it]?.username }
        val permalink = postId?.let { pid ->
            postRepository.findById(pid).orElse(null)?.let { "https://www.instagram.com/p/${it.postCode}/" }
        }
        return InsightDto(
            id = id.toString(),
            type = type,
            severity = severity,
            title = title,
            body = body,
            actionText = actionText,
            probableCause = probableCause,
            username = username,
            permalink = permalink,
            status = status,
            createdAt = createdAt
        )
    }

    /** Przybliżenie daty ostatniego posta z granulacji tygodniowej (do oceny świeżości profilu). */
    private fun approxLastPost(lastActiveWeek: LocalDate?): Instant? =
        lastActiveWeek?.plusDays(6)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()
}
