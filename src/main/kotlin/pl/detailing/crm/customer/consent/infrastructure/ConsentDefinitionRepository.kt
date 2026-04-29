package pl.detailing.crm.customer.consent.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import pl.detailing.crm.shared.ProtocolStage
import java.util.*

@Repository
interface ConsentDefinitionRepository : JpaRepository<ConsentDefinitionEntity, UUID> {

    @Query("SELECT cd FROM ConsentDefinitionEntity cd WHERE cd.id = :id AND cd.studioId = :studioId")
    fun findByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): ConsentDefinitionEntity?

    @Query("SELECT cd FROM ConsentDefinitionEntity cd WHERE cd.studioId = :studioId AND cd.isActive = true ORDER BY cd.displayOrder ASC")
    fun findActiveByStudioId(@Param("studioId") studioId: UUID): List<ConsentDefinitionEntity>

    @Query("SELECT cd FROM ConsentDefinitionEntity cd WHERE cd.studioId = :studioId")
    fun findAllByStudioId(@Param("studioId") studioId: UUID): List<ConsentDefinitionEntity>

    @Query("SELECT cd FROM ConsentDefinitionEntity cd WHERE cd.slug = :slug AND cd.studioId = :studioId")
    fun findBySlugAndStudioId(
        @Param("slug") slug: String,
        @Param("studioId") studioId: UUID
    ): ConsentDefinitionEntity?

    @Query("SELECT cd FROM ConsentDefinitionEntity cd WHERE cd.studioId = :studioId AND cd.stage = :stage AND cd.isActive = true ORDER BY cd.displayOrder ASC")
    fun findActiveByStudioIdAndStage(
        @Param("studioId") studioId: UUID,
        @Param("stage") stage: ProtocolStage
    ): List<ConsentDefinitionEntity>
}
