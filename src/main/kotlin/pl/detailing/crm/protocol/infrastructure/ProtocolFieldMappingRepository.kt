package pl.detailing.crm.protocol.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.*

/**
 * Repository for ProtocolFieldMapping entities.
 * All queries are filtered by studioId for multi-tenancy.
 */
@Repository
interface ProtocolFieldMappingRepository : JpaRepository<ProtocolFieldMappingEntity, UUID> {

    @Query("SELECT pfm FROM ProtocolFieldMappingEntity pfm WHERE pfm.id = :id AND pfm.studioId = :studioId")
    fun findByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): ProtocolFieldMappingEntity?

    @Query("""
        SELECT pfm FROM ProtocolFieldMappingEntity pfm
        WHERE pfm.templateId = :templateId
        AND pfm.studioId = :studioId
        ORDER BY pfm.pdfFieldName
    """)
    fun findAllByTemplateIdAndStudioId(
        @Param("templateId") templateId: UUID,
        @Param("studioId") studioId: UUID
    ): List<ProtocolFieldMappingEntity>

    @Query("""
        SELECT pfm FROM ProtocolFieldMappingEntity pfm
        WHERE pfm.templateId = :templateId
        AND pfm.pdfFieldName = :pdfFieldName
        AND pfm.studioId = :studioId
    """)
    fun findByTemplateIdAndPdfFieldNameAndStudioId(
        @Param("templateId") templateId: UUID,
        @Param("pdfFieldName") pdfFieldName: String,
        @Param("studioId") studioId: UUID
    ): ProtocolFieldMappingEntity?

    @Modifying
    @Query("""
        DELETE FROM ProtocolFieldMappingEntity pfm
        WHERE pfm.templateId = :templateId
        AND pfm.studioId = :studioId
    """)
    fun deleteAllByTemplateIdAndStudioId(
        @Param("templateId") templateId: UUID,
        @Param("studioId") studioId: UUID
    )
}
