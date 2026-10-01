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

    /**
     * Zdjęcia do (ponownego) wygenerowania miniatury, dla ThumbnailBackfillJob: najpierw bez
     * miniatury, potem z miniaturą sprzed poprawki orientacji EXIF (klucz bez `.upright.jpg`);
     * w obu grupach najnowsze pierwsze.
     */
    @Query(
        "SELECT p FROM VehiclePhotoEntity p " +
        "WHERE p.thumbnailFileId IS NULL " +
        "   OR (p.thumbnailFileId LIKE 'thumbs/%' AND p.thumbnailFileId NOT LIKE '%.upright.jpg') " +
        "ORDER BY CASE WHEN p.thumbnailFileId IS NULL THEN 0 ELSE 1 END, p.uploadedAt DESC"
    )
    fun findMissingThumbnails(pageable: Pageable): List<VehiclePhotoEntity>
}
