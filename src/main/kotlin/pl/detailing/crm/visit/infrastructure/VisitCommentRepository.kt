package pl.detailing.crm.visit.infrastructure

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface VisitCommentRepository : JpaRepository<VisitCommentEntity, UUID> {
    /**
     * Find all comments for a specific visit, ordered by creation date
     */
    fun findByVisitIdOrderByCreatedAtAsc(visitId: UUID): List<VisitCommentEntity>

    /**
     * Find all non-deleted comments for a specific visit
     */
    fun findByVisitIdAndIsDeletedFalseOrderByCreatedAtAsc(visitId: UUID): List<VisitCommentEntity>

    /**
     * Find paginated non-deleted comments across all visits for a given vehicle.
     * Joins through VisitEntity to filter by vehicleId and enforce studio isolation.
     */
    @Query(
        value = """
            SELECT c FROM VisitCommentEntity c
            JOIN VisitEntity v ON c.visitId = v.id
            WHERE v.vehicleId = :vehicleId
            AND v.studioId = :studioId
            AND c.isDeleted = false
            ORDER BY c.createdAt DESC
        """,
        countQuery = """
            SELECT COUNT(c) FROM VisitCommentEntity c
            JOIN VisitEntity v ON c.visitId = v.id
            WHERE v.vehicleId = :vehicleId
            AND v.studioId = :studioId
            AND c.isDeleted = false
        """
    )
    fun findByVehicleIdAndStudioId(
        @Param("vehicleId") vehicleId: UUID,
        @Param("studioId") studioId: UUID,
        pageable: Pageable
    ): Page<VisitCommentEntity>
}

@Repository
interface VisitCommentRevisionRepository : JpaRepository<VisitCommentRevisionEntity, UUID> {
    /**
     * Find all revisions for a specific comment, ordered by change date
     */
    fun findByCommentIdOrderByChangedAtAsc(commentId: UUID): List<VisitCommentRevisionEntity>
}
