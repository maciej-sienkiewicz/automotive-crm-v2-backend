package pl.detailing.crm.instagram.ai.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface InstagramStyleRuleRepository : JpaRepository<InstagramStyleRuleEntity, UUID> {

    fun findByStudioIdOrderByCreatedAtAsc(studioId: UUID): List<InstagramStyleRuleEntity>

    fun findByStudioIdAndActiveTrueOrderByCreatedAtAsc(studioId: UUID): List<InstagramStyleRuleEntity>

    fun countByStudioIdAndActiveTrue(studioId: UUID): Long

    fun findByIdAndStudioId(id: UUID, studioId: UUID): InstagramStyleRuleEntity?
}
