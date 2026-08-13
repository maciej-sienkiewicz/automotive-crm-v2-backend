package pl.detailing.crm.instagram.infrastructure

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate
import java.util.*

@Repository
interface InstagramInsightRepository : JpaRepository<InstagramInsightEntity, UUID> {

    fun findByStudioIdOrderByCreatedAtDesc(studioId: UUID, pageable: Pageable): List<InstagramInsightEntity>

    fun findByStudioIdAndStatusOrderByCreatedAtDesc(
        studioId: UUID,
        status: String,
        pageable: Pageable
    ): List<InstagramInsightEntity>

    fun findByStudioIdAndId(studioId: UUID, id: UUID): InstagramInsightEntity?

    fun existsByStudioIdAndDedupKey(studioId: UUID, dedupKey: String): Boolean

    fun countByStudioIdAndWeekStart(studioId: UUID, weekStart: LocalDate): Long

    fun findByStudioIdAndCreatedAtAfterOrderByCreatedAtDesc(
        studioId: UUID,
        after: Instant
    ): List<InstagramInsightEntity>

    fun deleteByCreatedAtBefore(cutoff: Instant): Long

    fun deleteByProfileId(profileId: UUID)
}
