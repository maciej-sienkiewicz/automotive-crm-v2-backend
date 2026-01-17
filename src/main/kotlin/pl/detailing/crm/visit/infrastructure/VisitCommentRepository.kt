package pl.detailing.crm.visit.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
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
}

@Repository
interface VisitCommentRevisionRepository : JpaRepository<VisitCommentRevisionEntity, UUID> {
    /**
     * Find all revisions for a specific comment, ordered by change date
     */
    fun findByCommentIdOrderByChangedAtAsc(commentId: UUID): List<VisitCommentRevisionEntity>
}
