package pl.detailing.crm.phototags

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import pl.detailing.crm.shared.EntityNotFoundException
import pl.detailing.crm.shared.StudioId
import pl.detailing.crm.visit.infrastructure.VisitPhotoRepository
import pl.detailing.crm.vehicle.infrastructure.VehiclePhotoRepository
import java.util.UUID

/**
 * Updates the complete set of tags for a photo (visit photo or vehicle photo).
 *
 * The operation is a full replace: existing tags are removed and the provided set is saved.
 * Tags are looked up in visit_photos first, then vehicle_photos, using studio isolation.
 */
@Service
class UpdatePhotoTagsHandler(
    private val visitPhotoRepository: VisitPhotoRepository,
    private val vehiclePhotoRepository: VehiclePhotoRepository,
    private val photoTagRepository: PhotoTagRepository
) {

    @Transactional
    suspend fun handle(command: UpdatePhotoTagsCommand): UpdatePhotoTagsResult {
        val photoId = command.photoId
        val studioId = command.studioId.value

        val photoType = resolvePhotoType(photoId, studioId)

        val sanitizedTags = command.tags
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        photoTagRepository.deleteByPhotoIdAndType(photoId, photoType)

        val tagEntities = sanitizedTags.map { tag ->
            PhotoTagEntity(
                id = UUID.randomUUID(),
                photoId = photoId,
                photoType = photoType,
                studioId = studioId,
                tagName = tag
            )
        }
        photoTagRepository.saveAll(tagEntities)

        return UpdatePhotoTagsResult(tags = sanitizedTags)
    }

    private fun resolvePhotoType(photoId: UUID, studioId: UUID): PhotoSource {
        visitPhotoRepository.findByIdAndStudioId(photoId, studioId)?.let {
            return PhotoSource.VISIT_PHOTO
        }
        vehiclePhotoRepository.findByIdAndStudioId(photoId, studioId)?.let {
            return PhotoSource.VEHICLE_PHOTO
        }
        throw EntityNotFoundException("Zdjęcie nie zostało znalezione: $photoId")
    }
}

data class UpdatePhotoTagsCommand(
    val photoId: UUID,
    val studioId: StudioId,
    val tags: List<String>
) {
    companion object {
        fun of(photoId: String, studioId: StudioId, tags: List<String>) =
            UpdatePhotoTagsCommand(
                photoId = UUID.fromString(photoId),
                studioId = studioId,
                tags = tags
            )
    }
}

data class UpdatePhotoTagsResult(
    val tags: List<String>
)
