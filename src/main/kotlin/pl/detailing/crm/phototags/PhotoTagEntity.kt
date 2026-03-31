package pl.detailing.crm.phototags

import jakarta.persistence.*
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/**
 * Discriminator indicating which photo table the photo_id references.
 * Used for future gallery filtering: "show me all VISIT_PHOTO tagged with 'PPF'".
 */
enum class PhotoSource {
    VISIT_PHOTO,
    VEHICLE_PHOTO
}

/**
 * Stores tags assigned to individual photos.
 *
 * Design notes:
 * - photo_id references either visit_photos.id or vehicle_photos.id
 * - photo_type discriminates between the two tables (needed for gallery filtering)
 * - studio_id is stored here to enable efficient multi-tenant tag queries
 *   (e.g. "find all photos tagged 'PPF' in studio X") without joining parent tables
 * - (studio_id, tag_name) index supports future gallery endpoint filtering
 */
@Entity
@Table(
    name = "photo_tags",
    indexes = [
        Index(name = "idx_photo_tags_photo", columnList = "photo_id, photo_type"),
        Index(name = "idx_photo_tags_studio_tag", columnList = "studio_id, tag_name"),
        Index(name = "idx_photo_tags_tag_name", columnList = "tag_name")
    ]
)
class PhotoTagEntity(
    @Id
    @Column(name = "id", columnDefinition = "uuid")
    val id: UUID,

    @Column(name = "photo_id", nullable = false, columnDefinition = "uuid")
    val photoId: UUID,

    @Column(name = "photo_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    val photoType: PhotoSource,

    @Column(name = "studio_id", nullable = false, columnDefinition = "uuid")
    val studioId: UUID,

    @Column(name = "tag_name", nullable = false, length = 100)
    val tagName: String
)

@Repository
interface PhotoTagRepository : JpaRepository<PhotoTagEntity, UUID> {

    @Query("SELECT t.tagName FROM PhotoTagEntity t WHERE t.photoId = :photoId AND t.photoType = :photoType")
    fun findTagNamesByPhotoIdAndType(
        @Param("photoId") photoId: UUID,
        @Param("photoType") photoType: PhotoSource
    ): List<String>

    @Modifying
    @Transactional
    @Query("DELETE FROM PhotoTagEntity t WHERE t.photoId = :photoId AND t.photoType = :photoType")
    fun deleteByPhotoIdAndType(
        @Param("photoId") photoId: UUID,
        @Param("photoType") photoType: PhotoSource
    )

    /**
     * Find all photo IDs of a given type that contain ALL of the provided tags within a studio.
     * Used by the future gallery endpoint for AND-filtering: "show photos tagged with X AND Y".
     */
    @Query("""
        SELECT t.photoId FROM PhotoTagEntity t
        WHERE t.studioId = :studioId
          AND t.photoType = :photoType
          AND t.tagName IN :tags
        GROUP BY t.photoId
        HAVING COUNT(DISTINCT t.tagName) = :tagCount
    """)
    fun findPhotoIdsByStudioAndTypeMatchingAllTags(
        @Param("studioId") studioId: UUID,
        @Param("photoType") photoType: PhotoSource,
        @Param("tags") tags: List<String>,
        @Param("tagCount") tagCount: Long
    ): List<UUID>

    /**
     * Find all photo IDs of a given type that contain ANY of the provided tags within a studio.
     * Used by the future gallery endpoint for OR-filtering: "show photos tagged with X OR Y".
     */
    @Query("""
        SELECT DISTINCT t.photoId FROM PhotoTagEntity t
        WHERE t.studioId = :studioId
          AND t.photoType = :photoType
          AND t.tagName IN :tags
    """)
    fun findPhotoIdsByStudioAndTypeMatchingAnyTag(
        @Param("studioId") studioId: UUID,
        @Param("photoType") photoType: PhotoSource,
        @Param("tags") tags: List<String>
    ): List<UUID>
}
