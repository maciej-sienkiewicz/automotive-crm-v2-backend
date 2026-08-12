package pl.detailing.crm.batchorder.infrastructure

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "batch_order_entry_photos",
    indexes = [
        Index(name = "idx_batch_order_photos_entry", columnList = "entry_id"),
        Index(name = "idx_batch_order_photos_studio", columnList = "studio_id"),
        Index(name = "idx_batch_order_photos_uploaded_at", columnList = "uploaded_at")
    ]
)
class BatchOrderPhotoEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "entry_id", nullable = false, columnDefinition = "uuid")
    val entryId: UUID,

    @Column(name = "contractor_id", nullable = false, columnDefinition = "uuid")
    val contractorId: UUID,

    @Column(name = "file_id", nullable = false, length = 500)
    val fileId: String,

    @Column(name = "file_name", nullable = false, length = 500)
    val fileName: String,

    @Column(name = "description", columnDefinition = "TEXT")
    val description: String?,

    @Column(name = "uploaded_at", nullable = false, columnDefinition = "timestamp with time zone")
    val uploadedAt: Instant,

    @Column(name = "uploaded_by", columnDefinition = "uuid")
    val uploadedBy: UUID?,

    @Column(name = "uploaded_by_name", length = 255)
    val uploadedByName: String?,

    // S3 key of the pre-generated thumbnail; null until the backfill job creates it
    @Column(name = "thumbnail_file_id", nullable = true, length = 500)
    var thumbnailFileId: String? = null
)
