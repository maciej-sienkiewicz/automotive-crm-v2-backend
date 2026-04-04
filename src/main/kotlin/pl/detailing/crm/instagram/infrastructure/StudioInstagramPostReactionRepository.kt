package pl.detailing.crm.instagram.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.*

@Repository
interface StudioInstagramPostReactionRepository : JpaRepository<StudioInstagramPostReactionEntity, UUID> {

    fun findByStudioIdAndPostId(studioId: UUID, postId: UUID): StudioInstagramPostReactionEntity?
}
