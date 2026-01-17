package pl.detailing.crm.visit.domain

import pl.detailing.crm.shared.*
import java.time.Instant

/**
 * Visit comment with full audit trail
 * Supports soft delete and revision history
 */
data class VisitComment(
    val id: VisitCommentId,
    val visitId: VisitId,
    val type: CommentType,
    val content: String,
    val isDeleted: Boolean = false,

    // Creation audit
    val createdBy: UserId,
    val createdByName: String,
    val createdAt: Instant,

    // Update audit
    val updatedBy: UserId?,
    val updatedByName: String?,
    val updatedAt: Instant?,

    // Deletion audit
    val deletedBy: UserId?,
    val deletedByName: String?,
    val deletedAt: Instant?
) {
    /**
     * Check if comment was edited
     */
    fun wasEdited(): Boolean = updatedAt != null && updatedBy != null

    /**
     * Check if comment is visible (not soft deleted)
     */
    fun isVisible(): Boolean = !isDeleted
}

/**
 * Revision history entry for visit comment
 * Tracks all content changes
 */
data class VisitCommentRevision(
    val id: VisitCommentRevisionId,
    val commentId: VisitCommentId,
    val oldContent: String,
    val newContent: String,
    val changedBy: UserId,
    val changedByName: String,
    val changedAt: Instant
)
