package pl.detailing.crm.statistics.category.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ServiceCategoryRepository : JpaRepository<ServiceCategoryEntity, UUID> {

    @Query("SELECT c FROM ServiceCategoryEntity c WHERE c.id = :id AND c.studioId = :studioId")
    fun findByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): ServiceCategoryEntity?

    @Query("""
        SELECT c FROM ServiceCategoryEntity c
        WHERE c.studioId = :studioId
        ORDER BY c.name ASC
    """)
    fun findAllByStudioId(@Param("studioId") studioId: UUID): List<ServiceCategoryEntity>

    @Query("""
        SELECT c FROM ServiceCategoryEntity c
        WHERE c.studioId = :studioId AND c.isActive = true
        ORDER BY c.name ASC
    """)
    fun findActiveByStudioId(@Param("studioId") studioId: UUID): List<ServiceCategoryEntity>

    @Query("""
        SELECT c FROM ServiceCategoryEntity c
        WHERE c.studioId = :studioId AND c.name = :name AND c.isActive = true
    """)
    fun findActiveByStudioIdAndName(
        @Param("studioId") studioId: UUID,
        @Param("name") name: String
    ): ServiceCategoryEntity?

    @Query("""
        SELECT COUNT(c) > 0 FROM ServiceCategoryEntity c
        WHERE c.studioId = :studioId AND c.name = :name AND c.isActive = true AND c.id != :excludeId
    """)
    fun existsActiveByStudioIdAndNameExcludingId(
        @Param("studioId") studioId: UUID,
        @Param("name") name: String,
        @Param("excludeId") excludeId: UUID
    ): Boolean
}
