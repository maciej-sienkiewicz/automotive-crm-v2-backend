package pl.detailing.crm.instagram.analytics

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.instagram.infrastructure.InstagramPostSnapshotRepository
import pl.detailing.crm.instagram.infrastructure.InstagramProfileMetricsSnapshotRepository
import pl.detailing.crm.instagram.infrastructure.InstagramProfileStatsWeeklyEntity
import pl.detailing.crm.instagram.infrastructure.InstagramProfileStatsWeeklyRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.*

/**
 * Materializuje tygodniowe agregaty profilu do instagram_profile_stats_weekly.
 * Wywoływana po każdym syncu postów – endpointy analityczne czytają gotowe wiersze
 * zamiast przeliczać snapshoty przy każdym żądaniu.
 */
@Service
class InstagramAggregationService(
    private val postRepository: InstagramPostSnapshotRepository,
    private val metricsRepository: InstagramProfileMetricsSnapshotRepository,
    private val statsRepository: InstagramProfileStatsWeeklyRepository
) {
    private val log = LoggerFactory.getLogger(InstagramAggregationService::class.java)

    companion object {
        /** Pełne przeliczenie: 56 tygodni pokrywa roczne okno UI z zapasem. */
        const val FULL_WINDOW_WEEKS = 56

        /** Lekkie przeliczenie po dziennym syncu – tylko świeże tygodnie. */
        const val RECENT_WINDOW_WEEKS = 3
    }

    @Transactional
    fun recomputeProfile(profileId: UUID, weeks: Int = FULL_WINDOW_WEEKS) {
        val currentMonday = MetricsCalculator.currentWeekStart()
        val fromMonday = currentMonday.minusWeeks(weeks.toLong() - 1)
        val cutoffInstant = fromMonday.atStartOfDay(ZoneOffset.UTC).toInstant()

        val posts = postRepository.findByProfileIdAndTakenAtAfter(profileId, cutoffInstant)
        // Snapshoty od tygodnia przed oknem – potrzebne do policzenia delty pierwszego tygodnia
        val metrics = metricsRepository.findByProfileIdAndSnapshotDateAfterOrderBySnapshotDateAsc(
            profileId, fromMonday.minusDays(8)
        )

        val postsByWeek = posts.groupBy { MetricsCalculator.weekStart(it.takenAt) }
        val existingByWeek = statsRepository
            .findByProfileIdAndWeekStartGreaterThanEqual(profileId, fromMonday)
            .associateBy { it.weekStart }

        val now = Instant.now()
        val toSave = mutableListOf<InstagramProfileStatsWeeklyEntity>()

        (0 until weeks).forEach { offset ->
            val monday = currentMonday.minusWeeks(offset.toLong())
            val weekPosts = postsByWeek[monday] ?: emptyList()

            val followerEnd = lastFollowerCountOnOrBefore(metrics, monday.plusDays(6))
            val followerStart = lastFollowerCountOnOrBefore(metrics, monday.minusDays(1))
            val followerDelta = if (followerEnd != null && followerStart != null) followerEnd - followerStart else null

            val totalLikes = weekPosts.sumOf { it.likeCount }
            val totalComments = weekPosts.sumOf { it.commentCount }
            val views = weekPosts.mapNotNull { it.viewCount }
            val reels = weekPosts.count { MetricsCalculator.formatBucket(it.productType) == "REELS" }
            val carousels = weekPosts.count { MetricsCalculator.formatBucket(it.productType) == "CAROUSEL" }

            val entity = existingByWeek[monday] ?: InstagramProfileStatsWeeklyEntity(
                id = UUID.randomUUID(),
                profileId = profileId,
                weekStart = monday,
                postCount = 0,
                reelsCount = 0,
                carouselCount = 0,
                photoCount = 0,
                totalLikes = 0,
                totalComments = 0,
                totalViews = null,
                erPct = null,
                followerEnd = null,
                followerDelta = null,
                computedAt = now
            )

            entity.postCount = weekPosts.size
            entity.reelsCount = reels
            entity.carouselCount = carousels
            entity.photoCount = weekPosts.size - reels - carousels
            entity.totalLikes = totalLikes
            entity.totalComments = totalComments
            entity.totalViews = if (views.isNotEmpty()) views.sum() else null
            entity.erPct = MetricsCalculator.erPct(
                totalLikes.toLong(), totalComments.toLong(), weekPosts.size, followerEnd
            )
            entity.followerEnd = followerEnd
            entity.followerDelta = followerDelta
            entity.computedAt = now

            toSave += entity
        }

        statsRepository.saveAll(toSave)
        log.debug("Instagram agregacja: profil {} – przeliczono {} tygodni", profileId, weeks)
    }

    private fun lastFollowerCountOnOrBefore(
        metrics: List<pl.detailing.crm.instagram.infrastructure.InstagramProfileMetricsSnapshotEntity>,
        date: LocalDate
    ): Int? = metrics.lastOrNull { it.snapshotDate <= date && it.followerCount != null }?.followerCount
}
