package pl.detailing.crm.customer.consent.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.*

/**
 * Repository for ConsentTemplate entities.
 * All queries are filtered by studioId for multi-tenancy.
 */
@Repository
interface ConsentTemplateRepository : JpaRepository<ConsentTemplateEntity, UUID> {

    @Query("SELECT ct FROM ConsentTemplateEntity ct WHERE ct.id = :id AND ct.studioId = :studioId")
    fun findByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): ConsentTemplateEntity?

    @Query("""
        SELECT ct FROM ConsentTemplateEntity ct
        WHERE ct.definitionId = :definitionId
        AND ct.studioId = :studioId
        AND ct.isActive = true
    """)
    fun findActiveByDefinitionIdAndStudioId(
        @Param("definitionId") definitionId: UUID,
        @Param("studioId") studioId: UUID
    ): ConsentTemplateEntity?

    @Query("""
        SELECT ct FROM ConsentTemplateEntity ct
        WHERE ct.studioId = :studioId
        AND ct.isActive = true
    """)
    fun findAllActiveByStudioId(@Param("studioId") studioId: UUID): List<ConsentTemplateEntity>

    @Query("""
        SELECT ct FROM ConsentTemplateEntity ct
        WHERE ct.definitionId = :definitionId
        AND ct.studioId = :studioId
        ORDER BY ct.version DESC
    """)
    fun findAllByDefinitionIdAndStudioId(
        @Param("definitionId") definitionId: UUID,
        @Param("studioId") studioId: UUID
    ): List<ConsentTemplateEntity>

    @Query("""
        SELECT MAX(ct.version) FROM ConsentTemplateEntity ct
        WHERE ct.definitionId = :definitionId
        AND ct.studioId = :studioId
    """)
    fun findMaxVersionByDefinitionId(
        @Param("definitionId") definitionId: UUID,
        @Param("studioId") studioId: UUID
    ): Int?

    @Modifying
    @Query("""
        UPDATE ConsentTemplateEntity ct
        SET ct.isActive = false
        WHERE ct.definitionId = :definitionId
        AND ct.studioId = :studioId
    """)
    fun deactivateAllByDefinitionId(
        @Param("definitionId") definitionId: UUID,
        @Param("studioId") studioId: UUID
    )
}
