package pl.detailing.crm.vehicle.infrastructure

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface VehiclePhotoRepository : JpaRepository<VehiclePhotoEntity, UUID> {

    @Query("""
        SELECT p FROM VehiclePhotoEntity p
        WHERE p.id = :photoId AND p.vehicle.studioId = :studioId
    """)
    fun findByIdAndStudioId(
        @Param("photoId") photoId: UUID,
        @Param("studioId") studioId: UUID
    ): VehiclePhotoEntity?

    /** Photos without a generated thumbnail, newest first — consumed by the thumbnail backfill job. */
    @Query("SELECT p FROM VehiclePhotoEntity p WHERE p.thumbnailFileId IS NULL ORDER BY p.uploadedAt DESC")
    fun findMissingThumbnails(pageable: Pageable): List<VehiclePhotoEntity>
}
