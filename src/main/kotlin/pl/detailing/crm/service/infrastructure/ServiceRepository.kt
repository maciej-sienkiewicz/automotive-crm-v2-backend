package pl.detailing.crm.service.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ServiceRepository : JpaRepository<ServiceEntity, UUID> {

    @Query("SELECT s FROM ServiceEntity s WHERE s.id = :id AND s.studioId = :studioId")
    fun findByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): ServiceEntity?

    @Query("SELECT s FROM ServiceEntity s WHERE s.studioId = :studioId AND s.isActive = true")
    fun findActiveByStudioId(@Param("studioId") studioId: UUID): List<ServiceEntity>

    @Query("""
        SELECT s FROM ServiceEntity s 
        WHERE s.studioId = :studioId 
        AND s.name = :name 
        AND s.isActive = true
    """)
    fun findActiveByStudioIdAndName(
        @Param("studioId") studioId: UUID,
        @Param("name") name: String
    ): ServiceEntity?

    @Query("""
        SELECT COUNT(s) > 0 FROM ServiceEntity s 
        WHERE s.studioId = :studioId 
        AND s.name = :name 
        AND s.isActive = true
    """)
    fun existsActiveByStudioIdAndName(
        @Param("studioId") studioId: UUID,
        @Param("name") name: String
    ): Boolean
}