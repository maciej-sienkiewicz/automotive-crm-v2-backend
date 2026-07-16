package pl.detailing.crm.visitcard.upsell.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface VisitUpsellSuggestionRepository : JpaRepository<VisitUpsellSuggestionEntity, UUID> {

    @Query("""
        SELECT s FROM VisitUpsellSuggestionEntity s
        WHERE s.visitId = :visitId AND s.studioId = :studioId
        ORDER BY s.createdAt
    """)
    fun findAllByVisitIdAndStudioId(
        @Param("visitId") visitId: UUID,
        @Param("studioId") studioId: UUID
    ): List<VisitUpsellSuggestionEntity>

    @Query("""
        SELECT s FROM VisitUpsellSuggestionEntity s
        WHERE s.id = :id AND s.studioId = :studioId
    """)
    fun findByIdAndStudioId(
        @Param("id") id: UUID,
        @Param("studioId") studioId: UUID
    ): VisitUpsellSuggestionEntity?

    @Query("""
        SELECT s FROM VisitUpsellSuggestionEntity s
        WHERE s.visitId = :visitId AND s.studioId = :studioId AND s.status = :status
        ORDER BY s.createdAt
    """)
    fun findAllByVisitIdAndStudioIdAndStatus(
        @Param("visitId") visitId: UUID,
        @Param("studioId") studioId: UUID,
        @Param("status") status: UpsellSuggestionStatus
    ): List<VisitUpsellSuggestionEntity>
}
