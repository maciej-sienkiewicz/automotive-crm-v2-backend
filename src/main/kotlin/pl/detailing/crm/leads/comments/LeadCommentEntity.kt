package pl.detailing.crm.leads.comments

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "lead_comments",
    indexes = [
        Index(name = "idx_lead_comments_lead_id", columnList = "lead_id, created_at"),
        Index(name = "idx_lead_comments_studio", columnList = "studio_id")
    ]
)
class LeadCommentEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "lead_id", nullable = false, columnDefinition = "uuid")
    val leadId: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "content", nullable = false, columnDefinition = "text")
    var content: String,

    @Column(name = "created_by", nullable = false, columnDefinition = "uuid")
    val createdBy: UUID,

    @Column(name = "created_by_name", nullable = false, length = 200)
    val createdByName: String,

    @Column(name = "created_at", nullable = false, columnDefinition = "timestamp with time zone")
    val createdAt: Instant,

    @Column(name = "updated_by", nullable = true, columnDefinition = "uuid")
    var updatedBy: UUID?,

    @Column(name = "updated_by_name", nullable = true, length = 200)
    var updatedByName: String?,

    @Column(name = "updated_at", nullable = true, columnDefinition = "timestamp with time zone")
    var updatedAt: Instant?,

    @Column(name = "deleted_by", nullable = true, columnDefinition = "uuid")
    var deletedBy: UUID?,

    @Column(name = "deleted_by_name", nullable = true, length = 200)
    var deletedByName: String?,

    @Column(name = "deleted_at", nullable = true, columnDefinition = "timestamp with time zone")
    var deletedAt: Instant?
)
