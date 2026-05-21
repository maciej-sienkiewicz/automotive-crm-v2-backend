package pl.detailing.crm.instagram.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.*

@Repository
interface InstagramStorySnapshotRepository : JpaRepository<InstagramStorySnapshotEntity, UUID> {

    fun findByProfileIdAndTakenAtAfterOrderByTakenAtDesc(
        profileId: UUID,
        cutoff: Instant
    ): List<InstagramStorySnapshotEntity>

    fun existsByStoryId(storyId: String): Boolean

    fun findByStoryIdIn(storyIds: List<String>): List<InstagramStorySnapshotEntity>

    fun deleteByProfileId(profileId: UUID)
}
