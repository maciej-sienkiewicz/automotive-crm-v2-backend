package pl.detailing.crm.instagram.summary

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.instagram.infrastructure.InstagramPostSnapshotRepository
import pl.detailing.crm.instagram.infrastructure.InstagramProfileRepository
import pl.detailing.crm.instagram.infrastructure.StudioInstagramProfileRepository
import pl.detailing.crm.shared.InstagramProfileStatus
import pl.detailing.crm.shared.StudioId
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

data class WeeklyStatDto(
    val weekStart: String,   // "YYYY-MM-DD" – Monday of that week
    val avgLikes: Double,
    val avgComments: Double,
    val postCount: Int
)

data class InstagramProfileSummaryDto(
    val id: String,
    val profileId: String,
    val username: String,
    val status: InstagramProfileStatus,
    val apiError: Boolean,
    val addedAt: Instant,
    val postCount: Int,
    val avgLikes: Double,
    val avgComments: Double,
    val avgViews: Double?,
    val postsPerWeek: Double,
    val lastPostAt: Instant?,
    val weeklyStats: List<WeeklyStatDto>
)

data class GetCompetitionSummaryQuery(
    val studioId: StudioId,
    val weeks: Int = 52
)

@Service
class GetCompetitionSummaryHandler(
    private val studioProfileRepository: StudioInstagramProfileRepository,
    private val profileRepository: InstagramProfileRepository,
    private val postSnapshotRepository: InstagramPostSnapshotRepository
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

        return activeStudioProfiles.mapNotNull { sp ->
            val gp = globalProfiles[sp.profileId] ?: return@mapNotNull null

            val posts = postSnapshotRepository
                .findByProfileIdOrderByTakenAtDesc(sp.profileId)
                .filter { it.takenAt >= cutoff }

            val weeklyStats = computeWeeklyStats(posts.map { post ->
                PostData(
                    likeCount = post.likeCount,
                    commentCount = post.commentCount,
                    viewCount = post.viewCount,
                    takenAt = post.takenAt
                )
            })

            val postCount = posts.size
            val avgLikes = if (postCount > 0) posts.sumOf { it.likeCount }.toDouble() / postCount else 0.0
            val avgComments = if (postCount > 0) posts.sumOf { it.commentCount }.toDouble() / postCount else 0.0
            val viewPosts = posts.filter { it.viewCount != null }
            val avgViews = if (viewPosts.isNotEmpty())
                viewPosts.sumOf { it.viewCount!! }.toDouble() / viewPosts.size
            else null
            val postsPerWeek = if (weeklyStats.isNotEmpty())
                postCount.toDouble() / weeklyStats.size
            else 0.0
            val lastPostAt = posts.firstOrNull()?.takenAt

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
                weeklyStats = weeklyStats
            )
        }
    }

    private data class PostData(
        val likeCount: Int,
        val commentCount: Int,
        val viewCount: Long?,
        val takenAt: Instant
    )

    private fun computeWeeklyStats(posts: List<PostData>): List<WeeklyStatDto> {
        val formatter = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC)

        val grouped = posts.groupBy { post ->
            post.takenAt
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        }

        return grouped.entries
            .sortedBy { it.key }
            .map { (monday, weekPosts) ->
                val count = weekPosts.size
                WeeklyStatDto(
                    weekStart = formatter.format(monday.atStartOfDay(ZoneOffset.UTC).toInstant()),
                    avgLikes = weekPosts.sumOf { it.likeCount }.toDouble() / count,
                    avgComments = weekPosts.sumOf { it.commentCount }.toDouble() / count,
                    postCount = count
                )
            }
    }
}
