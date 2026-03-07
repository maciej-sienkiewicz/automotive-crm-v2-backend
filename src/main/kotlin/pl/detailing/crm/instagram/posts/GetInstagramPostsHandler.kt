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
    val scrapedAt: Instant,
    /** "feed_item" | "clips" | "carousel_container" – null dla starych rekordów */
    val productType: String?,
    /** Liczba slajdów karuzeli; dla innych typów 1 */
    val carouselMediaCount: Int,
    /** Hashtagi wyekstrahowane z caption */
    val hashtags: List<String>,
    /** Suma lajków i komentarzy */
    val engagementScore: Int
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
                scrapedAt = p.scrapedAt,
                productType = p.productType,
                carouselMediaCount = p.carouselMediaCount ?: 0,
                hashtags = p.hashtags?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
                engagementScore = p.likeCount + p.commentCount
            )
        }
    }
}
