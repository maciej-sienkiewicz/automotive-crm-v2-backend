package pl.detailing.crm.gallery

import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service
import pl.detailing.crm.phototags.PhotoTagRepository
import java.util.UUID

/**
 * Studio-wide distinct tag names, cached in Redis.
 *
 * The list is near-static (it only changes when someone edits a photo's tags)
 * but was previously recomputed with a DISTINCT scan on every gallery request.
 * A short TTL (see CacheConfig) plus explicit eviction on tag updates keeps it
 * fresh without the per-request query.
 */
@Service
class GalleryTagService(
    private val photoTagRepository: PhotoTagRepository
) {

    @Cacheable("gallery-available-tags", key = "#studioId.toString()")
    fun getAvailableTags(studioId: UUID): List<String> =
        photoTagRepository.findDistinctTagNamesByStudio(studioId)

    @CacheEvict("gallery-available-tags", key = "#studioId.toString()")
    fun evictAvailableTags(studioId: UUID) {
        // Eviction handled by the annotation.
    }
}
