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
    fun countByEntryIdAndStudioId(entryId: UUID, studioId: UUID): Long

    /**
     * Liczba zdjęć na wpis, jednym zapytaniem dla całej listy. Wiersz = [entryId, count];
     * wpisy bez zdjęć nie mają wiersza. Pustej kolekcji [ids] nie przekazuj — patrz
     * [photoCountsByEntryId].
     */
    @Query("""
        SELECT p.entryId, COUNT(p) FROM BatchOrderPhotoEntity p
        WHERE p.studioId = :studioId AND p.entryId IN :ids
        GROUP BY p.entryId
    """)
    fun countGroupedByEntryId(studioId: UUID, ids: Collection<UUID>): List<Array<Any>>

    /** Photos without a generated thumbnail, newest first — consumed by the thumbnail backfill job. */
    @Query("SELECT p FROM BatchOrderPhotoEntity p WHERE p.thumbnailFileId IS NULL ORDER BY p.uploadedAt DESC")
    fun findMissingThumbnails(pageable: Pageable): List<BatchOrderPhotoEntity>
}

/** [BatchOrderPhotoRepository.countGroupedByEntryId] jako mapa; pusta lista wpisów nie idzie do bazy. */
fun BatchOrderPhotoRepository.photoCountsByEntryId(studioId: UUID, ids: Collection<UUID>): Map<UUID, Int> {
    if (ids.isEmpty()) return emptyMap()
    return countGroupedByEntryId(studioId, ids).associate { row ->
        (row[0] as UUID) to (row[1] as Number).toInt()
    }
}
