package pl.detailing.crm.visit.technicalnote

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface VisitTechnicalNoteHistoryRepository : JpaRepository<VisitTechnicalNoteHistoryEntity, UUID> {

    @Query(
        """
        SELECT h FROM VisitTechnicalNoteHistoryEntity h
        WHERE h.visitId = :visitId AND h.studioId = :studioId
        ORDER BY h.changedAt DESC
        """
    )
    fun findByVisitIdAndStudioIdOrderByChangedAtDesc(
        @Param("visitId") visitId: UUID,
        @Param("studioId") studioId: UUID
    ): List<VisitTechnicalNoteHistoryEntity>
}
