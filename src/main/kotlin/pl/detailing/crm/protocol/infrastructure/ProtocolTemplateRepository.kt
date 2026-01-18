package pl.detailing.crm.protocol.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.*

/**
 * Repository for ProtocolTemplate entities.
 * All queries are filtered by studioId for multi-tenancy.
 */
@Repository
interface ProtocolTemplateRepository : JpaRepository<ProtocolTemplateEntity, UUID> {

    @Query("SELECT pt FROM ProtocolTemplateEntity pt WHERE pt.id = :id AND pt.studioId = :studioId")
    fun findByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): ProtocolTemplateEntity?

    @Query("SELECT pt FROM ProtocolTemplateEntity pt WHERE pt.studioId = :studioId ORDER BY pt.name")
    fun findAllByStudioId(@Param("studioId") studioId: UUID): List<ProtocolTemplateEntity>

    @Query("SELECT pt FROM ProtocolTemplateEntity pt WHERE pt.studioId = :studioId AND pt.isActive = true ORDER BY pt.name")
    fun findActiveByStudioId(@Param("studioId") studioId: UUID): List<ProtocolTemplateEntity>

    @Query("""
        SELECT COUNT(pt) > 0 FROM ProtocolTemplateEntity pt
        WHERE pt.studioId = :studioId
        AND pt.name = :name
        AND pt.isActive = true
    """)
    fun existsActiveByStudioIdAndName(
        @Param("studioId") studioId: UUID,
        @Param("name") name: String
    ): Boolean
}
