package pl.detailing.crm.instagram.summary

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.instagram.infrastructure.InstagramPostSnapshotRepository
import pl.detailing.crm.instagram.infrastructure.InstagramProfileMetricsSnapshotRepository
import pl.detailing.crm.instagram.infrastructure.InstagramProfileRepository
import pl.detailing.crm.instagram.infrastructure.InstagramStorySnapshotRepository
import pl.detailing.crm.instagram.infrastructure.StudioInstagramProfileRepository
import pl.detailing.crm.shared.InstagramProfileStatus
import pl.detailing.crm.shared.StudioId
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

data class WeeklyStatDto(
    val weekStart: String,
    val postCount: Int,
    val storyCount: Int,
    val totalLikes: Int,
    val totalComments: Int,
    val avgLikes: Double,
    val avgComments: Double
)

data class DailyStoryStatDto(
    /** Data "YYYY-MM-DD" (UTC). Tylko dni z przynajmniej 1 story. */
    val date: String,
    val storyCount: Int
)

data class FollowerSnapshotDto(
    /** Data snapshotu "YYYY-MM-DD" (UTC). */
    val date: String,
    val followerCount: Int?
)

data class InstagramProfileSummaryDto(
    val id: String,
    val profileId: String,
    val username: String,
    val status: InstagramProfileStatus,
    val apiError: Boolean,
    val addedAt: Instant,
    // ── Metryki postów ──
    val postCount: Int,
    val avgLikes: Double,
    val avgComments: Double,
    val avgViews: Double?,
    val postsPerWeek: Double,
    val lastPostAt: Instant?,
    val weeklyStats: List<WeeklyStatDto>,
    val avgEngagement: Double,
    // ── Aktywność stories ──
    val storiesPerWeek: Double,
    /** Dzienny rozkład stories w oknie `weeks` – jeden wpis per dzień z przynajmniej 1 story. */
    val dailyStoryStats: List<DailyStoryStatDto>,
    // ── Metryki profilu (z /user/details) ──
    val followerCount: Int?,
    val followingCount: Int?,
    val mediaCount: Int?,
    val hasContactData: Boolean,
    val isVerified: Boolean,
    val isBusiness: Boolean,
    val accountType: Int?,
    val category: String?,
    val externalUrl: String?,
    val biography: String?,
    val hasHighlightReels: Boolean,
    val totalClipsCount: Int,
    val isPrivate: Boolean,
    val detailsLastSyncedAt: Instant?,
    // ── Trend followerów (historia dzienna w oknie `weeks`) ──
    val followerHistory: List<FollowerSnapshotDto>
)

data class GetCompetitionSummaryQuery(
    val studioId: StudioId,
    val weeks: Int = 52
)

@Service
class GetCompetitionSummaryHandler(
    private val studioProfileRepository: StudioInstagramProfileRepository,
    private val profileRepository: InstagramProfileRepository,
    private val postSnapshotRepository: InstagramPostSnapshotRepository,
    private val storySnapshotRepository: InstagramStorySnapshotRepository,
    private val metricsSnapshotRepository: InstagramProfileMetricsSnapshotRepository
) {

    @Transactional(readOnly = true)
    fun handle(query: GetCompetitionSummaryQuery): List<InstagramProfileSummaryDto> {
        val activeStudioProfiles = studioProfileRepository
            .findByStudioId(query.studioId.value)
            .filter { it.status == InstagramProfileStatus.ACTIVE }

        if (activeStudioProfiles.isEmpty()) return emptyList()

        val profileIds = activeStudioProfiles.map { it.profileId }.toSet()
        val globalProfiles = profileRepository.findAllById(profileIds).associateBy { it.id }

        val cutoff = Instant.now().minusSeconds(query.weeks.toLong() * 7 * 24 * 3600)
        val cutoffDate = LocalDate.now(ZoneOffset.UTC).minusWeeks(query.weeks.toLong())

        return activeStudioProfiles.mapNotNull { sp ->
            val gp = globalProfiles[sp.profileId] ?: return@mapNotNull null

            // ── Posty ──────────────────────────────────────────────────────────
            val allPosts = postSnapshotRepository.findByProfileIdOrderByTakenAtDesc(sp.profileId)
            val posts = allPosts.filter { it.takenAt >= cutoff }

            val avgEngagement = if (allPosts.isNotEmpty())
                allPosts.sumOf { it.likeCount + it.commentCount }.toDouble() / allPosts.size
            else 0.0

            // ── Stories ────────────────────────────────────────────────────────
            val stories = storySnapshotRepository
                .findByProfileIdAndTakenAtAfterOrderByTakenAtDesc(sp.profileId, cutoff)

            // ── Tygodniowe statystyki (posty + stories) ────────────────────────
            val weeklyStats = computeWeeklyStats(
                posts = posts.map { post ->
                    PostData(
                        likeCount = post.likeCount,
                        commentCount = post.commentCount,
                        viewCount = post.viewCount,
                        takenAt = post.takenAt
                    )
                },
                stories = stories.map { it.takenAt },
                weeks = query.weeks
            )

            // ── Agregaty postów ─────────────────────────────────────────────────
            val postCount = posts.size
            val avgLikes = if (postCount > 0) posts.sumOf { it.likeCount }.toDouble() / postCount else 0.0
            val avgComments = if (postCount > 0) posts.sumOf { it.commentCount }.toDouble() / postCount else 0.0
            val viewPosts = posts.filter { it.viewCount != null }
            val avgViews = if (viewPosts.isNotEmpty())
                viewPosts.sumOf { it.viewCount!! }.toDouble() / viewPosts.size
            else null
            // Mianownik = query.weeks bo weeklyStats zawsze ma dokładnie tyle wpisów
            val postsPerWeek = postCount.toDouble() / query.weeks
            val lastPostAt = posts.firstOrNull()?.takenAt

            // ── Aktywność stories ───────────────────────────────────────────────
            val storiesPerWeek = stories.size.toDouble() / query.weeks

            val dailyStoryStats = stories
                .groupBy { it.takenAt.atZone(ZoneOffset.UTC).toLocalDate() }
                .entries
                .sortedBy { it.key }
                .map { (date, dayStories) ->
                    DailyStoryStatDto(date = date.toString(), storyCount = dayStories.size)
                }

            // ── Historia followerów ─────────────────────────────────────────────
            val followerHistory = metricsSnapshotRepository
                .findByProfileIdAndSnapshotDateAfterOrderBySnapshotDateAsc(sp.profileId, cutoffDate)
                .map { snap ->
                    FollowerSnapshotDto(
                        date = snap.snapshotDate.toString(),
                        followerCount = snap.followerCount
                    )
                }

            InstagramProfileSummaryDto(
                id = sp.id.toString(),
                profileId = gp.id.toString(),
                username = gp.username,
                status = sp.status,
                apiError = gp.apiError,
                addedAt = sp.createdAt,
                postCount = postCount,
                avgLikes = avgLikes,
                avgComments = avgComments,
                avgViews = avgViews,
                postsPerWeek = postsPerWeek,
                lastPostAt = lastPostAt,
                weeklyStats = weeklyStats,
                avgEngagement = avgEngagement,
                storiesPerWeek = storiesPerWeek,
                dailyStoryStats = dailyStoryStats,
                followerCount = gp.followerCount,
                followingCount = gp.followingCount,
                mediaCount = gp.mediaCount,
                hasContactData = gp.hasContactData,
                isVerified = gp.isVerified,
                isBusiness = gp.isBusiness,
                accountType = gp.accountType,
                category = gp.category,
                externalUrl = gp.externalUrl,
                biography = gp.biography,
                hasHighlightReels = gp.hasHighlightReels,
                totalClipsCount = gp.totalClipsCount,
                isPrivate = gp.isPrivate,
                detailsLastSyncedAt = gp.detailsLastSyncedAt,
                followerHistory = followerHistory
            )
        }
    }

    private data class PostData(
        val likeCount: Int,
        val commentCount: Int,
        val viewCount: Long?,
        val takenAt: Instant
    )

    private fun computeWeeklyStats(
        posts: List<PostData>,
        stories: List<Instant>,
        weeks: Int
    ): List<WeeklyStatDto> {
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC)
        val currentMonday = LocalDate.now(ZoneOffset.UTC)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        val weekKey = { instant: Instant ->
            instant.atZone(ZoneOffset.UTC)
                .toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        }

        val postsByWeek = posts.groupBy { weekKey(it.takenAt) }
        val storiesByWeek = stories.groupBy { weekKey(it) }

        // Zawsze generuj dokładnie `weeks` tygodni wstecz od bieżącego poniedziałku.
        // Tygodnie bez danych dostają zera – brak luk w tablicy.
        return (weeks - 1 downTo 0).map { offset ->
            val monday = currentMonday.minusWeeks(offset.toLong())
            val weekPosts = postsByWeek[monday] ?: emptyList()
            val weekStories = storiesByWeek[monday] ?: emptyList()
            val count = weekPosts.size
            val totalLikes = weekPosts.sumOf { it.likeCount }
            val totalComments = weekPosts.sumOf { it.commentCount }
            WeeklyStatDto(
                weekStart = formatter.format(monday.atStartOfDay(ZoneOffset.UTC).toInstant()),
                postCount = count,
                storyCount = weekStories.size,
                totalLikes = totalLikes,
                totalComments = totalComments,
                avgLikes = if (count > 0) totalLikes.toDouble() / count else 0.0,
                avgComments = if (count > 0) totalComments.toDouble() / count else 0.0
            )
        }
    }
}
