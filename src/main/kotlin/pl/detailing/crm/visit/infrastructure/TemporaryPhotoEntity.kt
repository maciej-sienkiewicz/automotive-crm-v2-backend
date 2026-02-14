package pl.detailing.crm.visit.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import pl.detailing.crm.shared.PhotoType
import java.time.Instant
import java.util.UUID

@Entity
@Table(
    name = "temporary_photos",
    indexes = [
        Index(name = "idx_temp_photos_session_id", columnList = "session_id"),
        Index(name = "idx_temp_photos_claimed", columnList = "claimed"),
        Index(name = "idx_temp_photos_uploaded_at", columnList = "uploaded_at")
    ]
)
class TemporaryPhotoEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "session_id", nullable = false, columnDefinition = "uuid")
    val sessionId: UUID,

    @Column(name = "photo_type", nullable = false, length = 50)
    val photoType: String,

    @Column(name = "s3_key", nullable = false, length = 500)
    val s3Key: String,

    @Column(name = "file_name", nullable = false, length = 255)
    val fileName: String,

    @Column(name = "file_size", nullable = false)
    val fileSize: Long,

    @Column(name = "content_type", nullable = false, length = 100)
    val contentType: String,

    @Column(name = "uploaded_at", nullable = false, columnDefinition = "timestamp with time zone")
    val uploadedAt: Instant = Instant.now(),

    @Column(name = "claimed", nullable = false)
    var claimed: Boolean = false
) {
    fun toPhotoType(): PhotoType = PhotoType.valueOf(photoType)
}

@Repository
interface TemporaryPhotoRepository : org.springframework.data.jpa.repository.JpaRepository<TemporaryPhotoEntity, UUID> {

    @Query("SELECT p FROM TemporaryPhotoEntity p WHERE p.sessionId = :sessionId ORDER BY p.uploadedAt ASC")
    fun findBySessionId(@Param("sessionId") sessionId: UUID): List<TemporaryPhotoEntity>

    @Query("SELECT p FROM TemporaryPhotoEntity p WHERE p.sessionId = :sessionId AND p.claimed = false ORDER BY p.uploadedAt ASC")
    fun findUnclaimedBySessionId(@Param("sessionId") sessionId: UUID): List<TemporaryPhotoEntity>

    @Query("SELECT p FROM TemporaryPhotoEntity p WHERE p.id = :photoId AND p.sessionId = :sessionId")
    fun findByIdAndSessionId(
        @Param("photoId") photoId: UUID,
        @Param("sessionId") sessionId: UUID
    ): TemporaryPhotoEntity?

    @Modifying
    @Query("DELETE FROM TemporaryPhotoEntity p WHERE p.sessionId = :sessionId")
    fun deleteBySessionId(@Param("sessionId") sessionId: UUID)

    @Modifying
    @Query("UPDATE TemporaryPhotoEntity p SET p.claimed = true WHERE p.id IN :photoIds")
    fun markAsClaimed(@Param("photoIds") photoIds: List<UUID>)
}
