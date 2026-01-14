package pl.detailing.crm.customer.consent.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.*

/**
 * Repository for ConsentDefinition entities.
 * All queries are filtered by studioId for multi-tenancy.
 */
@Repository
interface ConsentDefinitionRepository : JpaRepository<ConsentDefinitionEntity, UUID> {

    @Query("SELECT cd FROM ConsentDefinitionEntity cd WHERE cd.id = :id AND cd.studioId = :studioId")
    fun findByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): ConsentDefinitionEntity?

    @Query("SELECT cd FROM ConsentDefinitionEntity cd WHERE cd.studioId = :studioId AND cd.isActive = true")
    fun findActiveByStudioId(@Param("studioId") studioId: UUID): List<ConsentDefinitionEntity>

    @Query("SELECT cd FROM ConsentDefinitionEntity cd WHERE cd.slug = :slug AND cd.studioId = :studioId")
    fun findBySlugAndStudioId(
        @Param("slug") slug: String,
        @Param("studioId") studioId: UUID
    ): ConsentDefinitionEntity?

    @Query("SELECT CASE WHEN COUNT(cd) > 0 THEN true ELSE false END FROM ConsentDefinitionEntity cd WHERE cd.slug = :slug AND cd.studioId = :studioId")
    fun existsBySlugAndStudioId(
        @Param("slug") slug: String,
        @Param("studioId") studioId: UUID
    ): Boolean
}
