package pl.detailing.crm.batchorder.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface BatchOrderPhotoRepository : JpaRepository<BatchOrderPhotoEntity, UUID> {
    fun findByEntryIdAndStudioId(entryId: UUID, studioId: UUID): List<BatchOrderPhotoEntity>
    fun findByStudioId(studioId: UUID): List<BatchOrderPhotoEntity>
    fun findByIdAndStudioId(id: UUID, studioId: UUID): BatchOrderPhotoEntity?
}
