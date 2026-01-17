package pl.detailing.crm.visit.infrastructure

import jakarta.persistence.*
import pl.detailing.crm.shared.*
import pl.detailing.crm.visit.domain.VisitComment
import pl.detailing.crm.visit.domain.VisitCommentRevision
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "visit_comments",
    indexes = [
        Index(name = "idx_visit_comments_visit_id", columnList = "visit_id"),
        Index(name = "idx_visit_comments_type", columnList = "type"),
        Index(name = "idx_visit_comments_created_at", columnList = "visit_id, created_at"),
        Index(name = "idx_visit_comments_is_deleted", columnList = "is_deleted")
    ]
)
class VisitCommentEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "visit_id", nullable = false, columnDefinition = "uuid")
    val visitId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 50)
    var type: CommentType,

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    var content: String,

    @Column(name = "is_deleted", nullable = false)
    var isDeleted: Boolean = false,

    // Creation audit
    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "created_by_name", nullable = false, length = 200)
    val createdByName: String,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant,

    // Update audit
    @Column(name = "updated_by", columnDefinition = "uuid")
    var updatedBy: UUID?,

    @Column(name = "updated_by_name", length = 200)
    var updatedByName: String?,

    @Column(name = "updated_at", columnDefinition = "timestamp with time zone")
    var updatedAt: Instant?,

    // Deletion audit
    @Column(name = "deleted_by", columnDefinition = "uuid")
    var deletedBy: UUID?,

    @Column(name = "deleted_by_name", length = 200)
    var deletedByName: String?,

    @Column(name = "deleted_at", columnDefinition = "timestamp with time zone")
    var deletedAt: Instant?
) {
    fun toDomain(): VisitComment = VisitComment(
        id = VisitCommentId(id),
        visitId = VisitId(visitId),
        type = type,
        content = content,
        isDeleted = isDeleted,
        createdBy = UserId(createdBy),
        createdByName = createdByName,
        createdAt = createdAt,
        updatedBy = updatedBy?.let { UserId(it) },
        updatedByName = updatedByName,
        updatedAt = updatedAt,
        deletedBy = deletedBy?.let { UserId(it) },
        deletedByName = deletedByName,
        deletedAt = deletedAt
    )

    companion object {
        fun fromDomain(comment: VisitComment): VisitCommentEntity =
            VisitCommentEntity(
                id = comment.id.value,
                visitId = comment.visitId.value,
                type = comment.type,
                content = comment.content,
                isDeleted = comment.isDeleted,
                createdBy = comment.createdBy.value,
                createdByName = comment.createdByName,
                createdAt = comment.createdAt,
                updatedBy = comment.updatedBy?.value,
                updatedByName = comment.updatedByName,
                updatedAt = comment.updatedAt,
                deletedBy = comment.deletedBy?.value,
                deletedByName = comment.deletedByName,
                deletedAt = comment.deletedAt
            )
    }
}

@Entity
@Table(
    name = "visit_comment_revisions",
    indexes = [
        Index(name = "idx_visit_comment_revisions_comment_id", columnList = "comment_id"),
        Index(name = "idx_visit_comment_revisions_changed_at", columnList = "comment_id, changed_at")
    ]
)
class VisitCommentRevisionEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "comment_id", nullable = false, columnDefinition = "uuid")
    val commentId: UUID,

    @Column(name = "old_content", nullable = false, columnDefinition = "TEXT")
    val oldContent: String,

    @Column(name = "new_content", nullable = false, columnDefinition = "TEXT")
    val newContent: String,

    @Column(name = "changed_by", nullable = false, columnDefinition = "uuid")
    val changedBy: UUID,

    @Column(name = "changed_by_name", nullable = false, length = 200)
    val changedByName: String,

    @Column(name = "changed_at", nullable = false, columnDefinition = "timestamp with time zone")
    val changedAt: Instant
) {
    fun toDomain(): VisitCommentRevision = VisitCommentRevision(
        id = VisitCommentRevisionId(id),
        commentId = VisitCommentId(commentId),
        oldContent = oldContent,
        newContent = newContent,
        changedBy = UserId(changedBy),
        changedByName = changedByName,
        changedAt = changedAt
    )

    companion object {
        fun fromDomain(revision: VisitCommentRevision): VisitCommentRevisionEntity =
            VisitCommentRevisionEntity(
                id = revision.id.value,
                commentId = revision.commentId.value,
                oldContent = revision.oldContent,
                newContent = revision.newContent,
                changedBy = revision.changedBy.value,
                changedByName = revision.changedByName,
                changedAt = revision.changedAt
            )
    }
}
