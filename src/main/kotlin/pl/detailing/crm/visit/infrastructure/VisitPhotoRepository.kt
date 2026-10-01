package pl.detailing.crm.visit.infrastructure

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface VisitPhotoRepository : JpaRepository<VisitPhotoEntity, UUID> {

    @Query("""
        SELECT p FROM VisitPhotoEntity p
        WHERE p.id = :photoId AND p.visit.studioId = :studioId
    """)
    fun findByIdAndStudioId(
        @Param("photoId") photoId: UUID,
        @Param("studioId") studioId: UUID
    ): VisitPhotoEntity?

    /**
     * Zdjęcia do (ponownego) wygenerowania miniatury, dla ThumbnailBackfillJob: najpierw bez
     * miniatury, potem z miniaturą sprzed poprawki orientacji EXIF (klucz bez `.upright.jpg`);
     * w obu grupach najnowsze pierwsze.
     */
    @Query(
        "SELECT p FROM VisitPhotoEntity p " +
        "WHERE p.thumbnailFileId IS NULL " +
        "   OR (p.thumbnailFileId LIKE 'thumbs/%' AND p.thumbnailFileId NOT LIKE '%.upright.jpg') " +
        "ORDER BY CASE WHEN p.thumbnailFileId IS NULL THEN 0 ELSE 1 END, p.uploadedAt DESC"
    )
    fun findMissingThumbnails(pageable: Pageable): List<VisitPhotoEntity>
}
