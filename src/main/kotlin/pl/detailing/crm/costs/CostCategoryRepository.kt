package pl.detailing.crm.costs

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface CostCategoryRepository : JpaRepository<CostCategoryEntity, UUID> {

    @Query("SELECT c FROM CostCategoryEntity c WHERE c.id = :id AND c.studioId = :studioId")
    fun findByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): CostCategoryEntity?

    @Query("""
        SELECT c FROM CostCategoryEntity c
        WHERE c.studioId = :studioId AND c.isActive = true
        ORDER BY c.name ASC
    """)
    fun findActiveByStudioId(@Param("studioId") studioId: UUID): List<CostCategoryEntity>
}
