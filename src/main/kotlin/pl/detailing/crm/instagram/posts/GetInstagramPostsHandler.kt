package pl.detailing.crm.instagram.posts

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.instagram.infrastructure.InstagramPostSnapshotRepository
import pl.detailing.crm.instagram.infrastructure.StudioInstagramProfileRepository
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.shared.StudioInstagramProfileId
import java.time.Instant

data class InstagramPostDto(
    val id: String,
    val postPk: String,
    val postCode: String,
    val likeCount: Int,
    val commentCount: Int,
    val viewCount: Long?,
    val caption: String?,
    val takenAt: Instant,
    val scrapedAt: Instant
)

data class GetInstagramPostsQuery(
    val studioId: StudioId,
    val studioProfileId: StudioInstagramProfileId
)

@Service
class GetInstagramPostsHandler(
    private val studioProfileRepository: StudioInstagramProfileRepository,
    private val postSnapshotRepository: InstagramPostSnapshotRepository
) {

    @Transactional(readOnly = true)
    fun handle(query: GetInstagramPostsQuery): List<InstagramPostDto> {
        val studioProfile = studioProfileRepository.findByStudioIdAndId(
            query.studioId.value,
            query.studioProfileId.value
        ) ?: throw EntityNotFoundException(
            "Profil o id=${query.studioProfileId} nie istnieje w tym studio."
        )

        val posts = postSnapshotRepository.findByProfileIdOrderByTakenAtDesc(studioProfile.profileId)

        return posts.map { p ->
            InstagramPostDto(
                id = p.id.toString(),
                postPk = p.postPk,
                postCode = p.postCode,
                likeCount = p.likeCount,
                commentCount = p.commentCount,
                viewCount = p.viewCount,
                caption = p.caption,
                takenAt = p.takenAt,
                scrapedAt = p.scrapedAt
            )
        }
    }
}
