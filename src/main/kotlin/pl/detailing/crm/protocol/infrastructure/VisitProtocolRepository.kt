package pl.detailing.crm.protocol.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import pl.detailing.crm.shared.ProtocolStage
import pl.detailing.crm.shared.VisitProtocolStatus
import java.util.*

/**
 * Repository for VisitProtocol entities.
 * All queries are filtered by studioId for multi-tenancy.
 */
@Repository
interface VisitProtocolRepository : JpaRepository<VisitProtocolEntity, UUID> {

    @Query("SELECT vp FROM VisitProtocolEntity vp WHERE vp.id = :id AND vp.studioId = :studioId")
    fun findByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): VisitProtocolEntity?

    @Query("""
        SELECT vp FROM VisitProtocolEntity vp
        WHERE vp.visitId = :visitId
        AND vp.studioId = :studioId
        ORDER BY vp.stage, vp.createdAt
    """)
    fun findAllByVisitIdAndStudioId(
        @Param("visitId") visitId: UUID,
        @Param("studioId") studioId: UUID
    ): List<VisitProtocolEntity>

    @Query("""
        SELECT vp FROM VisitProtocolEntity vp
        WHERE vp.visitId = :visitId
        AND vp.studioId = :studioId
        AND vp.stage = :stage
        ORDER BY vp.createdAt
    """)
    fun findAllByVisitIdAndStudioIdAndStage(
        @Param("visitId") visitId: UUID,
        @Param("studioId") studioId: UUID,
        @Param("stage") stage: ProtocolStage
    ): List<VisitProtocolEntity>

    @Query("""
        SELECT vp FROM VisitProtocolEntity vp
        WHERE vp.visitId = :visitId
        AND vp.studioId = :studioId
        AND vp.status = :status
        ORDER BY vp.createdAt
    """)
    fun findAllByVisitIdAndStudioIdAndStatus(
        @Param("visitId") visitId: UUID,
        @Param("studioId") studioId: UUID,
        @Param("status") status: VisitProtocolStatus
    ): List<VisitProtocolEntity>

    @Query("""
        SELECT COUNT(vp) > 0 FROM VisitProtocolEntity vp
        WHERE vp.visitId = :visitId
        AND vp.studioId = :studioId
    """)
    fun existsByVisitIdAndStudioId(
        @Param("visitId") visitId: UUID,
        @Param("studioId") studioId: UUID
    ): Boolean

    @Query("""
        SELECT vp FROM VisitProtocolEntity vp
        WHERE vp.visitId = :visitId
        AND vp.id = :protocolId
        AND vp.studioId = :studioId
    """)
    fun findByVisitIdAndIdAndStudioId(
        @Param("visitId") visitId: UUID,
        @Param("protocolId") protocolId: UUID,
        @Param("studioId") studioId: UUID
    ): VisitProtocolEntity?

    @Query("""
        SELECT COALESCE(MAX(vp.version), 0) FROM VisitProtocolEntity vp
        WHERE vp.visitId = :visitId
        AND vp.studioId = :studioId
        AND vp.stage = :stage
        AND vp.templateId = :templateId
    """)
    fun findMaxVersionByVisitAndStageAndTemplate(
        @Param("visitId") visitId: UUID,
        @Param("studioId") studioId: UUID,
        @Param("stage") stage: ProtocolStage,
        @Param("templateId") templateId: UUID
    ): Int
}
