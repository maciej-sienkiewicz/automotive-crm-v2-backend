package pl.detailing.crm.instagram.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.*

@Repository
interface InstagramPostTopicRepository : JpaRepository<InstagramPostTopicEntity, UUID> {

    fun findByPostIdIn(postIds: Collection<UUID>): List<InstagramPostTopicEntity>

    /** Posty profilu bez etykiety – kandydaci do klasyfikacji. */
    @Query(
        """
        SELECT p FROM InstagramPostSnapshotEntity p
        WHERE p.profileId = :profileId
        AND NOT EXISTS (SELECT t.id FROM InstagramPostTopicEntity t WHERE t.postId = p.id)
        """
    )
    fun findUnclassifiedPosts(profileId: UUID): List<InstagramPostSnapshotEntity>

    fun deleteByPostIdIn(postIds: Collection<UUID>)
}
