package pl.detailing.crm.batchorder.infrastructure

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface BatchOrderPhotoRepository : JpaRepository<BatchOrderPhotoEntity, UUID> {
    fun findByEntryIdAndStudioId(entryId: UUID, studioId: UUID): List<BatchOrderPhotoEntity>
    fun findByStudioId(studioId: UUID): List<BatchOrderPhotoEntity>
    fun findByIdAndStudioId(id: UUID, studioId: UUID): BatchOrderPhotoEntity?

    /** Photos without a generated thumbnail, newest first — consumed by the thumbnail backfill job. */
    @Query("SELECT p FROM BatchOrderPhotoEntity p WHERE p.thumbnailFileId IS NULL ORDER BY p.uploadedAt DESC")
    fun findMissingThumbnails(pageable: Pageable): List<BatchOrderPhotoEntity>
}
